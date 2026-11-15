package kz.fin.backend.persistence;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine;
import kz.fin.backend.domain.EconomicKernel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class TrustedCrossFormDiagnosticIntegrationTest {
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
    @Autowired TrustedFinancialFormMapper mapper;
    @Autowired FactSetPublicationService publisher;
    @Autowired TrustedCrossFormDiagnosticService diagnostic;
    @Autowired TrustedFinancialPopulationService population;

    private static final YearMonth MONTH=YearMonth.of(2026,10);
    private static TrustedFinancialFormMapper.Cell cell(YearMonth period,String row,String field,String amount){
        return new TrustedFinancialFormMapper.Cell(row,field,period,new BigDecimal(amount));
    }
    private record Case(UUID org,UUID entity,UUID scope,UUID revision) {}
    private record Setup(UUID org,UUID entity,UUID scope,List<TrustedFinancialFormMapper.Mapped> mapped) {}

    private List<TrustedFinancialFormMapper.Mapped> source(Setup s,String form,YearMonth month,
                                                     String[][] fields){
        List<TrustedFinancialFormMapper.Cell> cells=new ArrayList<>();
        for(String[] field:fields)
            cells.add(cell(month,field[0],field[1],field[2]));
        UUID revision=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision
                (organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?, ?,?,'FIN-PILOT-V1',now())
            """,s.org(),revision,form);
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_intake(
                organization_id,source_revision_id,normalized_cell_fingerprint,
                source_document_hash,input_currency,amount_scale,expected_cell_count,
                reviewed_by,reviewed_at)
            VALUES (?,?,?,?,'KZT',1,?,'synthetic-reviewed-source',now())
            """,s.org(),revision,TrustedFinancialFormMapper.fingerprint(cells),
                "a".repeat(64),cells.size());
        return mapper.mapReviewedSource(s.org(),revision,s.entity(),s.scope(),"MANAGEMENT",cells);
    }
    private Case fixture(boolean full){return fixture(full,false);}

    private Case fixture(boolean full,boolean omitOneApprovedCashReceipt){
        Setup s=new Setup(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),new ArrayList<>());
        List<TrustedFinancialFormMapper.Mapped> all=new ArrayList<>();
        if(full) {
            all.addAll(source(s,"F01",MONTH.minusMonths(1),new String[][]{
                    {"balance","cash","100"},{"balance","receivables","50"}
            }));
            all.addAll(source(s,"F01",MONTH,new String[][]{
                    {"balance","assets","1000"},{"balance","liabilities","350"},
                    {"balance","equity","650"},{"balance","cash","120"},
                    {"balance","receivables","80"}
            }));
        }
        all.addAll(source(s,"F02",MONTH,new String[][]{
                {"pnl","netRevenue","400"},{"pnl","cogs","260"},
                {"pnl","operatingExpenses","90"}
        }));
        if(full){
            all.addAll(source(s,"F03",MONTH,new String[][]{
                    {"cf","operatingCash","35"},{"cf","investingCash","-10"},
                    {"cf","financingCash","-5"}
            }));
            all.addAll(source(s,"DF-CSH-01",MONTH,new String[][]{
                    {"receipt1","receipt","160"},{"receipt2","receipt","140"}
            }));
            all.addAll(source(s,"DF-CSH-02",MONTH,new String[][]{
                    {"payment1","payment","280"}
            }));
        }
        UUID revision=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at)
            VALUES (?,?,'APPROVED',now())
            """,revision,s.org());
        for(var row:all){
            if(omitOneApprovedCashReceipt && row.sourceRowId().equals("receipt2"))continue;
            String key=jdbc.queryForObject("SELECT fin_fact.fact_identity_hash(?)",String.class,row.factId());
            jdbc.update("""
                INSERT INTO fin_fact.fact_selection
                    (organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
                VALUES (?,?,?,?)
                """,s.org(),revision,key,row.factId());
        }
        publisher.publish(new FactSetPublicationService.Request(s.org(),s.entity(),s.scope(),
                EconomicKernel.ReportingBasis.MANAGEMENT,AnalysisContext.DataSpace.SYNTHETIC,
                revision,null));
        return new Case(s.org(),s.entity(),s.scope(),revision);
    }
    private static CrossFormDiagnosticEngine.Finding finding(CrossFormDiagnosticEngine.Report r,String code){
        return r.findings().stream().filter(f->f.ruleCode().equals(code)).findFirst().orElseThrow();
    }
    @Test void completeSixReportRelationshipsAreReproducibleAndPersistImmutableSnapshot(){
        var s=fixture(true);
        var report=diagnostic.evaluate(s.org(),s.revision(),MONTH);
        assertEquals(6,report.findings().size());
        assertEquals(15,report.evidence().size());
        assertEquals(CrossFormDiagnosticEngine.Verdict.RECONCILED,
                finding(report,"BALANCE_IDENTITY").verdict());
        assertEquals(CrossFormDiagnosticEngine.Verdict.RECONCILED,
                finding(report,"CASH_ROLLFORWARD").verdict());
        assertEquals(CrossFormDiagnosticEngine.Verdict.REVIEW,
                finding(report,"CASH_LEDGER_CASHFLOW_BRIDGE").verdict());
        assertEquals(0,new BigDecimal("-15").compareTo(finding(report,
                "OPERATING_RESULT_TO_CFO").computed().get("cfoDifferenceToOperatingResult")));
        var cash=findingSources(report);
        assertTrue(cash.stream().anyMatch(src->src.formCode().equals("F01")));
        assertTrue(cash.stream().anyMatch(src->src.formCode().equals("F03")));
        var first=diagnostic.assessAndPublish(s.org(),s.revision(),MONTH);
        var second=diagnostic.assessAndPublish(s.org(),s.revision(),MONTH);
        assertEquals(first.id(),second.id());
        assertFalse(first.reused());
        assertTrue(second.reused());
        assertEquals(64,first.snapshotHash().length());
        var stored=diagnostic.loadPublished(s.org(),first.id());
        assertEquals("CROSS_FORM_PILOT",stored.snapshot().path("ruleSetCode").asText());
        assertEquals(6,stored.snapshot().path("findings").size());
        assertEquals(0,stored.snapshot().path("nextEvidencePlan").path("nextForms").size());
        assertThrows(IllegalArgumentException.class,
                ()->diagnostic.loadPublished(UUID.randomUUID(),first.id()));
    }
    private static List<CrossFormDiagnosticEngine.Source> findingSources(
            CrossFormDiagnosticEngine.Report report){
        return finding(report,"CASH_ROLLFORWARD").sources();
    }
    @Test void onlyF02LoadedMeansMarginalCalculationButAllOtherRulesExplicitlyMissing(){
        var s=fixture(false);
        var report=diagnostic.evaluate(s.org(),s.revision(),MONTH);
        assertEquals(CrossFormDiagnosticEngine.Verdict.OBSERVATION,
                finding(report,"GROSS_MARGIN").verdict());
        var missing=finding(report,"CASH_ROLLFORWARD");
        assertEquals(CrossFormDiagnosticEngine.Verdict.MISSING_EVIDENCE,missing.verdict());
        assertTrue(missing.computed().isEmpty());
        assertTrue(missing.missing().stream().anyMatch(m->m.code().equals("CFO")));
        var snapshot=diagnostic.assessAndPublish(s.org(),s.revision(),MONTH);
        var next=diagnostic.loadPublished(s.org(),snapshot.id()).snapshot()
                .path("nextEvidencePlan").path("nextForms");
        assertEquals(2,next.size());
        assertEquals("F03",next.get(0).path("formCode").asText());
        assertEquals("F01",next.get(1).path("formCode").asText());

    }
    @Test void partialApprovedCashSourceCannotProduceEvenAnApparentlyValidCrossFormSignal(){
        var s=fixture(true,true);
        var error=assertThrows(IllegalArgumentException.class,
                ()->diagnostic.evaluate(s.org(),s.revision(),MONTH));
        assertEquals("SOURCE_POPULATION_INCOMPLETE_OR_UNMAPPED",error.getMessage());
    }
    @Test void foreignTenantOrUnpublishedRevisionMustNotRevealAnyFinancialEvidence(){
        var s=fixture(true);
        assertThrows(IllegalArgumentException.class,
                ()->diagnostic.evaluate(UUID.randomUUID(),s.revision(),MONTH));
        assertThrows(IllegalArgumentException.class,
                ()->diagnostic.evaluate(s.org(),UUID.randomUUID(),MONTH));
    }
}
