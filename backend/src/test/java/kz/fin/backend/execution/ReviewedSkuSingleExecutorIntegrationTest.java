package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.domain.EconomicKernel;
import kz.fin.backend.persistence.ReviewedFinancialIntakeService;
import kz.fin.backend.persistence.TrustedFinancialFormMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL-backed golden: a separately reviewed F02 REV_NET (1000) must match the
 * independently reviewed, immutable SKU sales/returns/cancellations journal.
 * FIN-01 / FIN-02 are not substituted for an authorized sales population.
 */
@SpringBootTest(properties={"fin.methods.con.enabled=true","fin.execution.enabled=false"})
@Testcontainers
class ReviewedSkuSingleExecutorIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ReviewedFinancialIntakeService intake;
    @Autowired ReviewedSkuPopulationLoader loader;
    @Autowired SingleExecutorScheduler scheduler;
    @Autowired CompositeCoreReportPublisher coreReports;
    @Autowired ObjectMapper json;

    private static final YearMonth FEB=YearMonth.of(2026,2);
    private static final LocalDate START=FEB.atDay(1);
    private static final LocalDate END=FEB.plusMonths(1).atDay(1);
    private static final byte[] ORIGINAL="{\"fixture\":\"synthetic-verified-json\"}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String DOC_HASH=sha256(ORIGINAL);
    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch(java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
    private record Fixture(UUID organization,UUID entity,UUID scope,
                           UUID revision,String snapshotHash) {}
    private record Source(Fixture fixture,UUID id) {}

    private static TrustedFinancialFormMapper.Cell cell(String code,String value) {
        return new TrustedFinancialFormMapper.Cell("pnl",code,FEB,new BigDecimal(value));
    }

    private Fixture publishedF02() {
        jdbc.update("""
            INSERT INTO fin_calc.metric_contract
                 (metric_code,metric_version,measure_kind,unit_code,qualification,method_ref)
            VALUES ('REV_NET','1.0.0','FLOW','MONEY','QUALIFIED','CON01_F02_REVIEWED_GOLDEN')
            ON CONFLICT DO NOTHING
            """);
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),scope=UUID.randomUUID();
        var submitted=intake.submitManual("f02-author",
            new ReviewedFinancialIntakeService.IntakeRequest(
                org,entity,scope,"F02","MANAGEMENT","KZT",1,
                List.of(cell("netRevenue","1000"),cell("cogs","600"),
                        cell("operatingExpenses","200"))));
        assertEquals("APPROVED",intake.review("independent-f02-reviewer",org,
                submitted.id(),true,"Verified F02 statements").decision());
        var pub=intake.publishReviewedActual(
            new ReviewedFinancialIntakeService.PublishRequest(
                org,entity,scope,EconomicKernel.ReportingBasis.MANAGEMENT,
                List.of(submitted.id()),null));
        return new Fixture(org,entity,scope,pub.revisionId(),pub.snapshotHash());
    }

    private Source source(Fixture f,int lines,String net,boolean badSnapshot) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_con.sku_population_submission (
                id,organization_id,economic_entity_id,business_scope_id,
                fact_set_revision_id,published_snapshot_hash,reporting_basis,
                data_space,period_start,period_end,currency,source_document_hash,
                source_system,submitted_by,expected_line_count,expected_net_total)
            VALUES (?,?,?,?,?,?,'MANAGEMENT','ACTUAL',?,?,'KZT',?,
                    'REVIEWED_SKU_LEDGER_TEST','sku-source-author',?,?)
            """,id,f.organization(),f.entity(),f.scope(),f.revision(),
            badSnapshot?"0".repeat(64):f.snapshotHash(),
            START,END,DOC_HASH,lines,new BigDecimal(net));
        jdbc.update("""
            INSERT INTO fin_con.sku_source_original(
                population_id,organization_id,original_bytes,sha256,original_length)
            VALUES (?,?,?,?,?)
            """,id,f.organization(),ORIGINAL,DOC_HASH,ORIGINAL.length);
        return new Source(f,id);
    }

    private void line(Source source,String line,String sku,String kind,
                      String original,String amount) {
        line(source,line,sku,kind,original,amount,START.plusDays(10),"KZT",DOC_HASH);
    }
    private void line(Source source,String line,String sku,String kind,
                      String original,String amount,LocalDate date,
                      String currency,String documentHash) {
        jdbc.update("""
            INSERT INTO fin_con.sku_sales_line
              (population_id,organization_id,source_line_ref,sku_code,event_kind,
               original_sale_ref,sale_date,currency,gross_amount,source_document_hash)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,source.id(),source.fixture().organization(),
            line,sku,kind,original,date,currency,new BigDecimal(amount),documentHash);
    }
    private void standardSales(Source s) {
        line(s,"sale-a","A","SALE",null,"500");
        line(s,"sale-b","B","SALE",null,"300");
        line(s,"sale-c","C","SALE",null,"200");
        line(s,"return-c","C","RETURN","sale-c","50");
        line(s,"sale-d","D","SALE",null,"50");
    }
    private void review(Source source,String actor){
        jdbc.update("""
            INSERT INTO fin_con.sku_population_review
                 (population_id,organization_id,decision,reviewed_by,review_reason)
            VALUES (?,?,'APPROVED',?,'Reviewed SKU invoice lines and F02 reconciliation')
            """,source.id(),source.fixture().organization(),actor);
    }
    private MethodTaskAdapter.TrustedRevision revision(Fixture f){
        return new MethodTaskAdapter.TrustedRevision(
            f.organization(),f.entity(),f.scope(),f.revision(),f.snapshotHash(),"ACTUAL");
    }
    private void toTerminal(Fixture f) {
        for(int i=0;i<80;i++){
            scheduler.tick();
            var done=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.analysis_run
                WHERE organization_id=? AND fact_set_revision_id=?
                  AND run_status IN ('COMPLETED','PARTIAL','FAILED')
                """,Long.class,f.organization(),f.revision());
            if(done==1L)return;
        }
        fail("CON01_CON02_RUN_NOT_TERMINAL");
    }
    private JsonNode result(Fixture f,String method) throws Exception {
        var rows=jdbc.query("""
            SELECT t.result_json::text
              FROM fin_exec.analysis_task t
              JOIN fin_exec.analysis_run r ON r.id=t.run_id
             WHERE r.organization_id=? AND r.fact_set_revision_id=?
               AND t.method_code=? AND t.task_status='COMPUTED'
            """,(rs,i)->rs.getString(1),f.organization(),f.revision(),method);
        assertEquals(1,rows.size(),"Exactly one computed task: "+method);
        return json.readTree(rows.getFirst());
    }
    private long planned(Fixture f,String method){
        return jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_task t
            JOIN fin_exec.analysis_run r ON r.id=t.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
              AND t.method_code=?
            """,Long.class,f.organization(),f.revision(),method);
    }

    @Test void completeApprovedSkuEvidenceRunsParetoThenAbcIntoImmutableCore() throws Exception {
        Fixture f=publishedF02();
        Source s=source(f,5,"1000",false);
        standardSales(s);
        assertThrows(DataAccessException.class,
                ()->review(s,"sku-source-author")); // four-eyes
        review(s,"independent-sku-reviewer");
        assertTrue(loader.load(revision(f)).isPresent());
        assertThrows(DataAccessException.class,
                ()->line(s,"sale-new","NEW","SALE",null,"10"));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE fin_con.sku_sales_line SET gross_amount=999
            WHERE population_id=? AND source_line_ref='sale-a'
            """,s.id()));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE fin_con.sku_population_review SET reviewed_by='forged'
            WHERE population_id=?
            """,s.id()));
        assertTrue(loader.load(new MethodTaskAdapter.TrustedRevision(
                UUID.randomUUID(),f.entity(),f.scope(),f.revision(),
                f.snapshotHash(),"ACTUAL")).isEmpty());
        assertTrue(loader.load(new MethodTaskAdapter.TrustedRevision(
                f.organization(),f.entity(),f.scope(),f.revision(),
                "0".repeat(64),"ACTUAL")).isEmpty());
        assertTrue(loader.load(new MethodTaskAdapter.TrustedRevision(
                f.organization(),f.entity(),f.scope(),f.revision(),
                f.snapshotHash(),"SYNTHETIC")).isEmpty());

        toTerminal(f);
        var pareto=result(f,QualifiedSkuParetoAdapter.CODE);
        var abc=result(f,QualifiedSkuAbcAdapter.CODE);
        assertEquals("CALCULATED",pareto.path("status").asText());
        assertEquals("CON01_PARETO_V1",pareto.path("methodVersion").asText());
        assertEquals("1000",pareto.path("reconciledNetRevenue").asText());
        assertEquals(List.of("A","B","C","D"),strings(pareto.path("rows"),"skuCode"));
        assertEquals(List.of("50","30","15","5"),numbers(pareto.path("rows"),"sharePercent"));
        assertEquals(List.of("50","80","95","100"),numbers(pareto.path("rows"),"cumulativePercent"));
        assertEquals(List.of("A","A","B","C"),strings(abc.path("rows"),"abcClass"));
        assertEquals("CON02_ABC_BOUNDARY_V1",abc.path("methodVersion").asText());
        assertEquals(pareto.path("skuPopulationFingerprint").asText(),
                abc.path("skuPopulationFingerprint").asText());
        assertEquals("METHOD_FAMILY_NOT_YET_APPROVED",
                abc.path("qualification").asText());

        assertEquals(1L,planned(f,QualifiedSkuParetoAdapter.CODE));
        assertEquals(1L,planned(f,QualifiedSkuAbcAdapter.CODE));
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_dependency d
            JOIN fin_exec.analysis_task abc ON abc.id=d.task_id
            JOIN fin_exec.analysis_task pareto ON pareto.id=d.prerequisite_task_id
            JOIN fin_exec.analysis_run r ON r.id=abc.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
              AND abc.method_code='CON-02/ABC_SALES'
              AND pareto.method_code='CON-01/PARETO_SALES'
            """,Long.class,f.organization(),f.revision()));

        // Other tests may have left unrelated terminal runs in the same
        // Testcontainers database. Locate THIS organization's immutable report.
        for(int i=0;i<40;i++) {
            coreReports.publishReady();
            Long found=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.core_report_snapshot
                WHERE organization_id=? AND fact_set_revision_id=?
                """,Long.class,f.organization(),f.revision());
            if(found==1L)break;
        }
        UUID report=jdbc.queryForObject("""
            SELECT id FROM fin_exec.core_report_snapshot
            WHERE organization_id=? AND fact_set_revision_id=?
            """,UUID.class,f.organization(),f.revision());
        var core=coreReports.load(f.organization(),report);
        assertEquals("PARTIAL",core.path("coreReadiness").asText());
        assertEquals("NOT_REQUESTED",core.path("mlReadiness").asText());
        assertTrue(core.path("methods").toString().contains(QualifiedSkuParetoAdapter.CODE));
        assertTrue(core.path("methods").toString().contains(QualifiedSkuAbcAdapter.CODE));
        assertTrue(core.path("methods").toString().contains("METHOD_FAMILY_NOT_YET_APPROVED"));
        assertThrows(IllegalArgumentException.class,
                ()->coreReports.load(UUID.randomUUID(),report));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE fin_exec.core_report_snapshot SET snapshot='{}'::jsonb WHERE id=?
            """,report));

        for(int i=0;i<4;i++) scheduler.tick();
        assertEquals(1L,planned(f,QualifiedSkuParetoAdapter.CODE));
        assertEquals(1L,planned(f,QualifiedSkuAbcAdapter.CODE));
        // Other test organizations can still have terminal runs to publish.
        // Idempotency is checked only for this same source revision.
        for(int i=0;i<5;i++) coreReports.publishReady();
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.core_report_snapshot
            WHERE organization_id=? AND fact_set_revision_id=?
            """,Long.class,f.organization(),f.revision()));
    }

    @Test void missingOrUnapprovedSkuEvidenceNeverSchedulesConMethods(){
        Fixture f=publishedF02();
        Source source=source(f,5,"1000",false);
        standardSales(source);
        assertTrue(loader.load(revision(f)).isEmpty());
        toTerminal(f);
        assertEquals(0L,planned(f,QualifiedSkuParetoAdapter.CODE));
        assertEquals(0L,planned(f,QualifiedSkuAbcAdapter.CODE));
    }

    @Test void lateApprovalQueuesSupplementalRunWithoutRewritingOriginalCore() throws Exception {
        Fixture f=publishedF02();
        Source s=source(f,5,"1000",false);
        standardSales(s);
        toTerminal(f);
        assertEquals(0L,planned(f,QualifiedSkuParetoAdapter.CODE));
        for(int n=0;n<20;n++) {
            coreReports.publishReady();
            var oldCount=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.core_report_snapshot snap
                WHERE snap.organization_id=? AND snap.fact_set_revision_id=?
                """,Long.class,f.organization(),f.revision());
            if(oldCount==1L) break;
        }
        UUID original=jdbc.queryForObject("""
            SELECT snap.id FROM fin_exec.core_report_snapshot snap
            WHERE snap.organization_id=? AND snap.fact_set_revision_id=?
            """,UUID.class,f.organization(),f.revision());
        var originalContent=coreReports.load(f.organization(),original);
        assertEquals("PARTIAL",originalContent.path("coreReadiness").asText());

        review(s,"independent-sku-reviewer");
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.fact_publication_outbox
            WHERE organization_id=? AND fact_set_revision_id=?
              AND event_type='SKU_POPULATION_APPROVED'
            """,Long.class,f.organization(),f.revision()));
        assertTrue(loader.load(revision(f)).isPresent());
        for(int i=0;i<100;i++) {
            scheduler.tick();
            var done=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.analysis_run r
                JOIN fin_exec.fact_publication_outbox event ON event.event_id=r.event_id
                WHERE r.organization_id=? AND r.fact_set_revision_id=?
                  AND event.event_type='SKU_POPULATION_APPROVED'
                  AND r.run_status IN ('COMPLETED','PARTIAL','FAILED')
                """,Long.class,f.organization(),f.revision());
            if(done==1L)break;
        }
        assertEquals(1L,planned(f,QualifiedSkuParetoAdapter.CODE));
        assertEquals(1L,planned(f,QualifiedSkuAbcAdapter.CODE));
        assertEquals("CALCULATED",result(f,QualifiedSkuParetoAdapter.CODE).path("status").asText());
        for(int i=0;i<30;i++) {
            coreReports.publishReady();
            Long count=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.core_report_snapshot snap
                WHERE snap.organization_id=? AND snap.fact_set_revision_id=?
                """,Long.class,f.organization(),f.revision());
            if(count==2L)break;
        }
        assertEquals(2L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.core_report_snapshot
            WHERE organization_id=? AND fact_set_revision_id=?
            """,Long.class,f.organization(),f.revision()));
        assertEquals(originalContent,coreReports.load(f.organization(),original));
        assertThrows(DataAccessException.class,()->review(s,"another-reviewer"));
    }

    @Test void duplicateOrOrphanReturnAndOverRefundAreRejected(){
        Fixture f=publishedF02();
        Source missingSale=source(f,3,"1000",false);
        line(missingSale,"sale-a","A","SALE",null,"500");
        line(missingSale,"sale-b","B","SALE",null,"600");
        line(missingSale,"return-z","B","RETURN","not-a-sale","100");
        assertThrows(DataAccessException.class,
                ()->review(missingSale,"independent-sku-reviewer"));
        assertThrows(DataAccessException.class,
                ()->line(missingSale,"sale-a","A","SALE",null,"500"));
        assertThrows(DataAccessException.class,
                ()->line(missingSale,"wrong-currency","X","SALE",null,"1",
                        START.plusDays(2),"USD",DOC_HASH));
        assertThrows(DataAccessException.class,
                ()->line(missingSale,"outside","X","SALE",null,"1",END,"KZT",DOC_HASH));
        assertThrows(DataAccessException.class,
                ()->line(missingSale,"wrong-source","X","SALE",null,"1",
                        START.plusDays(2),"KZT","a".repeat(64)));
    }

    @Test void overReturnAndConflictingFullCancellationCannotBeApproved(){
        Fixture f=publishedF02();
        Source s=source(f,5,"1000",false);
        line(s,"sale-a","A","SALE",null,"500");
        line(s,"sale-b","B","SALE",null,"300");
        line(s,"sale-c","C","SALE",null,"200");
        line(s,"return-c","C","RETURN","sale-c","250");
        line(s,"sale-d","D","SALE",null,"250");
        assertThrows(DataAccessException.class,
                ()->review(s,"independent-sku-reviewer"));

        // A partial refund of a fully cancelled invoice is forbidden.
        Fixture last=publishedF02();
        Source conflicting=source(last,8,"990",false);
        standardSales(conflicting);
        line(conflicting,"sale-e","E","SALE",null,"100");
        line(conflicting,"cancel-e","E","CANCELLATION","sale-e","100");
        line(conflicting,"return-e","E","RETURN","sale-e","10");
        assertThrows(DataAccessException.class,
                ()->review(conflicting,"independent-sku-reviewer"));
    }

    @Test void fullCancellationDoesNotInflateSkuGrossOrNetConcentration() throws Exception {
        Fixture f=publishedF02();
        Source s=source(f,7,"1000",false);
        standardSales(s);
        line(s,"sale-e","E","SALE",null,"100");
        line(s,"cancel-e","E","CANCELLATION","sale-e","100");
        review(s,"independent-sku-reviewer");
        toTerminal(f);
        var p=result(f,QualifiedSkuParetoAdapter.CODE);
        assertEquals(5,p.path("rows").size());
        assertEquals("E",p.path("rows").get(4).path("skuCode").asText());
        assertEquals(0,new BigDecimal("0").compareTo(
                new BigDecimal(p.path("rows").get(4).path("netSales").asText())));
        var a=result(f,QualifiedSkuAbcAdapter.CODE);
        assertEquals("C",a.path("rows").get(4).path("abcClass").asText());
    }

    @Test void internallyReconciledSkuTotalMustStillMatchIndependentReviewedF02(){
        Fixture f=publishedF02();
        Source s=source(f,5,"990",false);
        line(s,"sale-a","A","SALE",null,"500");
        line(s,"sale-b","B","SALE",null,"300");
        line(s,"sale-c","C","SALE",null,"200");
        line(s,"return-c","C","RETURN","sale-c","50");
        line(s,"sale-d","D","SALE",null,"40");
        // Internal SKU total is correctly 990; reviewed F02 is 1000.
        assertThrows(DataAccessException.class,
                ()->review(s,"independent-sku-reviewer"));
        assertTrue(loader.load(revision(f)).isEmpty());
    }

    @Test void crossSkuReturnIsNotAllowedEvenIfOverallNetWouldReconcile(){
        Fixture f=publishedF02();
        Source s=source(f,3,"1000",false);
        line(s,"sale-a","A","SALE",null,"500");
        line(s,"sale-b","B","SALE",null,"600");
        line(s,"return-a-on-b","A","RETURN","sale-b","100");
        // Total exactly matches F02; approval must reject wrong SKU lineage.
        assertThrows(DataAccessException.class,
                ()->review(s,"independent-sku-reviewer"));
    }

    @Test void mismatchedDeclaredRevenueAndPublishedSnapshotCannotBeApproved(){
        Fixture f=publishedF02();
        Source mismatch=source(f,5,"990",false);
        standardSales(mismatch);
        assertThrows(DataAccessException.class,
                ()->review(mismatch,"independent-sku-reviewer"));
        // Unique key allows only one manifest per revision/period, so use another revision.
        Fixture other=publishedF02();
        Source badHash=source(other,5,"1000",true);
        standardSales(badHash);
        assertThrows(DataAccessException.class,
                ()->review(badHash,"independent-sku-reviewer"));
        assertTrue(loader.load(revision(other)).isEmpty());
    }

    private static List<String> strings(JsonNode array,String key){
        var result=new ArrayList<String>();
        for(var entry:array)result.add(entry.path(key).asText());
        return result;
    }
    private static List<String> numbers(JsonNode array,String key){
        var result=new ArrayList<String>();
        for(var entry:array)result.add(
                new BigDecimal(entry.path(key).asText()).stripTrailingZeros().toPlainString());
        return result;
    }
}
