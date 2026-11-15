package kz.fin.backend.persistence;

import kz.fin.backend.analytics.*;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static kz.fin.backend.analytics.AutoIndicatorEngine.*;
import static kz.fin.backend.analytics.AutoReportProjection.*;

@SpringBootTest
@Testcontainers
class TrustedAutoReportIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties){
        properties.add("spring.datasource.url",postgres::getJdbcUrl);
        properties.add("spring.datasource.username",postgres::getUsername);
        properties.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FactAssertionJpaRepository facts;
    @Autowired FactSetPublicationService publications;
    @Autowired TrustedAutoReportService reports;

    private static final YearMonth JAN=YearMonth.of(2026,1),FEB=YearMonth.of(2026,2);
    private static final EconomicKernel.ReportingBasis BASIS=EconomicKernel.ReportingBasis.MANAGEMENT;
    record Seed(UUID org,UUID entity,UUID scope,UUID revision,
                AnalysisContext.DataSpace dataSpace) {
        SeriesKey key(String code){
            return new SeriesKey(org,entity,scope,revision,code,"v1",MeasureKind.FLOW,
                    "MONEY","KZT",BASIS,dataSpace,new TreeMap<>());
        }
    }

    private static EconomicKernel.TemporalReference month(YearMonth ym){
        return EconomicKernel.TemporalReference.interval(ym.atDay(1),ym.plusMonths(1).atDay(1));
    }
    private static EconomicKernel.TemporalReference twoMonths(){
        return EconomicKernel.TemporalReference.interval(JAN.atDay(1),FEB.plusMonths(1).atDay(1));
    }

    private void metric(String code){
        jdbc.update("""
           INSERT INTO fin_calc.metric_contract(
             metric_code,metric_version,measure_kind,unit_code,qualification,method_ref)
           VALUES (?,'v1','FLOW','MONEY','PILOT','SMOKE_ONLY')
           ON CONFLICT DO NOTHING
           """,code);
    }

    private Seed seed(UUID org,UUID entity,UUID scope,UUID parent,
                      AnalysisContext.DataSpace space,String januaryRevenue,
                      boolean includeCogs){
        metric("REV_NET");
        metric("COGS");
        UUID src=UUID.randomUUID(),revision=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision(
              organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES(?,?,'DF-SAL-01','v1',now())""",org,src);
        if(parent==null) jdbc.update("""
            INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at)
            VALUES (?,?,'APPROVED',now())""",revision,org);
        else jdbc.update("""
            INSERT INTO fin_fact.fact_set_revision(
                id,organization_id,parent_revision_id,status,approved_at)
            VALUES(?,?,?,'APPROVED',now())""",revision,org,parent);

        putFact(org,entity,scope,src,revision,"REV_NET",JAN,januaryRevenue);
        putFact(org,entity,scope,src,revision,"REV_NET",FEB,"150");
        if(includeCogs) putFact(org,entity,scope,src,revision,"COGS",FEB,"60");

        return new Seed(org,entity,scope,revision,space);
    }

    private void putFact(UUID org,UUID entity,UUID scope,UUID source,UUID revision,
                         String metric,YearMonth month,String amount){
        var key=new EconomicKernel.FactKey(org,entity,scope,metric,"v1",
                month(month),BASIS,"MONEY","KZT",new TreeMap<>());
        UUID assertionId=UUID.randomUUID();
        facts.saveAndFlush(FactAssertionJpaEntity.from(new EconomicKernel.FactAssertion(
                assertionId,key,EconomicKernel.EconomicValue.present(amount),
                source,Instant.parse("2026-11-15T00:00:00Z"))));
        String hash=jdbc.queryForObject("SELECT fin_fact.fact_identity_hash(?)",
                String.class,assertionId);
        jdbc.update("""
            INSERT INTO fin_fact.fact_selection(
               organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
            VALUES(?,?,?,?)""",org,revision,hash,assertionId);
    }

    private void publish(Seed seed,UUID expected){
        publications.publish(new FactSetPublicationService.Request(
                seed.org(),seed.entity(),seed.scope(),BASIS,seed.dataSpace(),seed.revision(),expected));
    }

    private static ReportDefinition report(Seed seed){
        SeriesKey rev=seed.key("REV_NET"),cogs=seed.key("COGS");
        return new ReportDefinition("FIN_MONTHLY_REPORT","v1",List.of(
                new Column("REV_MOM_PCT",400,"finance.revenue.mom",
                        new Compare(rev,month(JAN),month(FEB),Aggregation.FLOW_SUM,
                                ComparisonMode.PERCENT)),
                new Column("COGS_SHARE",500,"finance.cogs.percent",
                        new PercentOf(cogs,rev,month(FEB),Aggregation.FLOW_SUM)),
                new Column("REV_TWO_MONTHS",300,"finance.revenue.sum",
                        new Aggregate(rev,twoMonths(),Aggregation.FLOW_SUM)),
                new Column("REV_JAN",100,"finance.revenue.jan",
                        new Aggregate(rev,month(JAN),Aggregation.FLOW_SUM)),
                new Column("REV_FEB",200,"finance.revenue.feb",
                        new Aggregate(rev,month(FEB),Aggregation.FLOW_SUM))));
    }

    private static void eq(String expected,TrustedAutoReportService.StoredCell cell){
        assertEquals("CALCULATED",cell.status());
        assertEquals(0,new BigDecimal(expected).compareTo(cell.value()),cell.toString());
    }

    @Test void loadFromPublishedFactsCalculatesAndPersistsFiveOrderedColumns() {
        Seed s=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"100",true);
        publish(s,null);
        var receipt=reports.calculateAndPublish(s.org(),s.revision(),report(s));
        assertFalse(receipt.reused());
        assertEquals(64,receipt.inputFingerprint().length());
        assertEquals(64,receipt.cellsHash().length());
        var loaded=reports.loadPublished(s.org(),receipt.reportRunId());
        assertEquals(TrustedAutoReportService.SourceFreshness.CURRENT_SOURCE,
                loaded.sourceFreshness());
        assertEquals(List.of("REV_JAN","REV_FEB","REV_TWO_MONTHS","REV_MOM_PCT","COGS_SHARE"),
                loaded.cells().stream().map(TrustedAutoReportService.StoredCell::code).toList());
        eq("100",loaded.cells().get(0));
        eq("150",loaded.cells().get(1));
        eq("250",loaded.cells().get(2));
        eq("50",loaded.cells().get(3));
        eq("40",loaded.cells().get(4));
        assertEquals("PCT",loaded.cells().get(4).unit());
        assertNull(loaded.cells().get(4).currency());
        assertThrows(UnsupportedOperationException.class,()->loaded.cells().clear());
    }

    @Test void repeatedReportUsesSameSnapshotAndRejectsChangedDefinitionUnderSameVersion() {
        Seed s=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"100",true);
        publish(s,null);
        var first=reports.calculateAndPublish(s.org(),s.revision(),report(s));
        var second=reports.calculateAndPublish(s.org(),s.revision(),report(s));
        assertTrue(second.reused());
        assertEquals(first.reportRunId(),second.reportRunId());
        assertEquals(first.inputFingerprint(),second.inputFingerprint());

        var alternative=new ReportDefinition("FIN_MONTHLY_REPORT","v1",List.of(
                new Column("ONLY_REVENUE",100,"finance.revenue",
                        new Aggregate(s.key("REV_NET"),month(JAN),Aggregation.FLOW_SUM))));
        assertEquals("REPORT_VERSION_DEFINITION_COLLISION",
                assertThrows(IllegalArgumentException.class,
                        ()->reports.calculateAndPublish(s.org(),s.revision(),alternative))
                        .getMessage());
    }

    @Test void correctingJanuaryCreatesNewRunAndMakesOldSourceStale() {
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),scope=UUID.randomUUID();
        Seed old=seed(org,entity,scope,null,AnalysisContext.DataSpace.SYNTHETIC,"100",true);
        publish(old,null);
        var oldReceipt=reports.calculateAndPublish(org,old.revision(),report(old));
        Seed next=seed(org,entity,scope,old.revision(),
                AnalysisContext.DataSpace.SYNTHETIC,"110",true);
        publish(next,old.revision());
        var newReceipt=reports.calculateAndPublish(org,next.revision(),report(next));
        assertNotEquals(oldReceipt.reportRunId(),newReceipt.reportRunId());
        assertNotEquals(oldReceipt.inputFingerprint(),newReceipt.inputFingerprint());
        var oldView=reports.loadPublished(org,oldReceipt.reportRunId());
        var newView=reports.loadPublished(org,newReceipt.reportRunId());
        assertEquals(TrustedAutoReportService.SourceFreshness.STALE_SOURCE,
                oldView.sourceFreshness());
        assertEquals(TrustedAutoReportService.SourceFreshness.CURRENT_SOURCE,
                newView.sourceFreshness());
        eq("250",oldView.cells().get(2));
        eq("260",newView.cells().get(2));
        eq("40",newView.cells().get(4));
        assertEquals(0,new BigDecimal("36.363636363636")
                .compareTo(newView.cells().get(3).value()));
    }

    @Test void missingCogsIsIncompleteAndCannotBeSilentlyZeroFilled() {
        Seed s=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"100",false);
        publish(s,null);
        var receipt=reports.calculateAndPublish(s.org(),s.revision(),report(s));
        var view=reports.loadPublished(s.org(),receipt.reportRunId());
        var cogs=view.cells().get(4);
        assertEquals("INCOMPLETE",cogs.status());
        assertEquals("MISSING_PERIODS",cogs.reason());
        assertNull(cogs.value());
        eq("250",view.cells().get(2));
    }

    @Test void unknownMetricAndUnpublishedRevisionAreRejected() {
        Seed s=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"100",true);
        assertEquals("UNPUBLISHED_OR_FOREIGN_FACT_SET",
                assertThrows(IllegalArgumentException.class,
                        ()->reports.calculateAndPublish(s.org(),s.revision(),report(s)))
                        .getMessage());
        publish(s,null);
        SeriesKey unknown=new SeriesKey(s.org(),s.entity(),s.scope(),s.revision(),
                "UNREGISTERED_METRIC","v1",MeasureKind.FLOW,"MONEY","KZT",
                BASIS,s.dataSpace(),new TreeMap<>());
        var wrong=new ReportDefinition("UNKNOWN_REPORT","v1",List.of(
                new Column("UNKNOWN",100,"finance.unknown",
                        new Aggregate(unknown,month(JAN),Aggregation.FLOW_SUM))));
        assertEquals("UNKNOWN_METRIC_METHOD",
                assertThrows(IllegalArgumentException.class,
                        ()->reports.calculateAndPublish(s.org(),s.revision(),wrong))
                        .getMessage());
    }

    @Test void unqualifiedPilotMetricCannotBePublishedAsActual() {
        Seed s=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.ACTUAL,"100",true);
        publish(s,null);
        assertEquals("UNQUALIFIED_METRIC_FOR_ACTUAL",
                assertThrows(IllegalArgumentException.class,
                        ()->reports.calculateAndPublish(s.org(),s.revision(),report(s)))
                        .getMessage());
    }

    @Test void tenantIsolationPreventsBothReadsAndCalculations() {
        Seed s=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"100",true);
        publish(s,null);
        var receipt=reports.calculateAndPublish(s.org(),s.revision(),report(s));
        UUID outsider=UUID.randomUUID();
        assertEquals("REPORT_NOT_FOUND_OR_FORBIDDEN",
                assertThrows(IllegalArgumentException.class,
                        ()->reports.loadPublished(outsider,receipt.reportRunId())).getMessage());
        assertEquals("UNPUBLISHED_OR_FOREIGN_FACT_SET",
                assertThrows(IllegalArgumentException.class,
                        ()->reports.calculateAndPublish(outsider,s.revision(),report(s))).getMessage());
    }

    @Test void scopeAndUnitTypeCannotBeSpoofedInsideReportDefinition() {
        Seed s=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"100",true);
        publish(s,null);
        SeriesKey foreignScope=new SeriesKey(s.org(),s.entity(),UUID.randomUUID(),s.revision(),
                "REV_NET","v1",MeasureKind.FLOW,"MONEY","KZT",
                BASIS,s.dataSpace(),new TreeMap<>());
        var wrong=new ReportDefinition("BAD_SCOPE","v1",List.of(
                new Column("REV",100,"finance.rev",
                        new Aggregate(foreignScope,month(JAN),Aggregation.FLOW_SUM))));
        assertEquals("REPORT_SCOPE_MISMATCH",
                assertThrows(IllegalArgumentException.class,
                        ()->reports.calculateAndPublish(s.org(),s.revision(),wrong)).getMessage());

        SeriesKey incorrectKind=new SeriesKey(s.org(),s.entity(),s.scope(),s.revision(),
                "REV_NET","v1",MeasureKind.STOCK,"MONEY","KZT",
                BASIS,s.dataSpace(),new TreeMap<>());
        var wrongKind=new ReportDefinition("BAD_KIND","v1",List.of(
                new Column("REV",100,"finance.rev",
                        new Aggregate(incorrectKind,month(JAN),Aggregation.STOCK_CLOSING))));
        assertEquals("METRIC_CONTRACT_MISMATCH",
                assertThrows(IllegalArgumentException.class,
                        ()->reports.calculateAndPublish(s.org(),s.revision(),wrongKind)).getMessage());
    }
}
