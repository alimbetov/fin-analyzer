package kz.fin.backend.persistence;

import kz.fin.backend.analytics.AnalysisContext;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class TrustedFinancialFormMapperIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired TrustedFinancialFormMapper mapper;
    @Autowired FactSetPublicationService publication;
    @Autowired TrustedFinancialPopulationService population;

    private static final YearMonth OCT=YearMonth.of(2026,10);
    private static TrustedFinancialFormMapper.Cell cell(String row,String field,String value) {
        return new TrustedFinancialFormMapper.Cell(row,field,OCT,new BigDecimal(value));
    }
    private record Source(UUID org,UUID entity,UUID scope,UUID source) {}
    private Source approve(String form,int scale,List<TrustedFinancialFormMapper.Cell> cells) {
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),scope=UUID.randomUUID(),source=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision(
                organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?, ?,?,'FIN-PILOT-V1',now())
            """,org,source,form);
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_intake(
                organization_id,source_revision_id,normalized_cell_fingerprint,source_document_hash,
                input_currency,amount_scale,expected_cell_count,reviewed_by,reviewed_at)
            VALUES (?,?,?,?,'KZT',?,?,'synthetic-finance-review',now())
            """,org,source,TrustedFinancialFormMapper.fingerprint(cells),
                "a".repeat(64),scale,cells.size());
        return new Source(org,entity,scope,source);
    }
    private List<TrustedFinancialFormMapper.Mapped> map(Source source,List<TrustedFinancialFormMapper.Cell> cells) {
        return mapper.mapReviewedSource(source.org(),source.source(),source.entity(),source.scope(),
                "MANAGEMENT",cells);
    }
    @Test void F01BalancesAreMonthEndStocksAndThousandsAreConvertedExactly() {
        var cells=List.of(cell("balance","assets","100.25"),cell("balance","liabilities","40"),
                cell("balance","equity","60.25"));
        Source source=approve("F01",1000,cells);
        var mapped=map(source,cells);
        assertEquals(3,mapped.size());
        assertEquals(Set.of("ASSETS","LIABILITIES","EQUITY"),
                new HashSet<>(mapped.stream().map(TrustedFinancialFormMapper.Mapped::metricCode).toList()));
        assertTrue(mapped.stream().allMatch(f->f.start().equals(OCT.atEndOfMonth())
                && f.end().equals(OCT.atEndOfMonth().plusDays(1))));
        assertEquals(0,new BigDecimal("100250").compareTo(mapped.stream()
                .filter(f->f.metricCode().equals("ASSETS")).findFirst().orElseThrow().canonicalAmount()));
        assertEquals(3L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_fact.fact_origin
            WHERE organization_id=? AND source_revision_id=?
            """,Long.class,source.org(),source.source()));
    }

    @Test void F02AndF03AndCashFormsUseDistinctCanonicalMappingAndPeriodSemantics() {
        Map<String,String[]> samples=Map.of(
                "F02",new String[]{"netRevenue","REV_NET"},
                "F03",new String[]{"operatingCash","CFO"},
                "DF-CSH-01",new String[]{"receipt","CASH_IN"},
                "DF-CSH-02",new String[]{"payment","CASH_OUT"});
        for(var entry:samples.entrySet()){
            var cells=List.of(cell("row01",entry.getValue()[0],"27.5"));
            var source=approve(entry.getKey(),1,cells);
            var mapped=map(source,cells);
            assertEquals(1,mapped.size());
            assertEquals(entry.getValue()[1],mapped.getFirst().metricCode());
            assertEquals(OCT.atDay(1),mapped.getFirst().start());
            assertEquals(OCT.plusMonths(1).atDay(1),mapped.getFirst().end());
            assertEquals(0,new BigDecimal("27.5").compareTo(mapped.getFirst().canonicalAmount()));
        }
    }
    @Test void omittedOrAlteredCellCannotPassImmutableApprovedFingerprint() {
        var cells=List.of(cell("row1","netRevenue","100"),cell("row2","cogs","50"));
        var source=approve("F02",1,cells);
        assertThrows(IllegalArgumentException.class,()->map(source,List.of(cells.getFirst())));
        assertThrows(IllegalArgumentException.class,()->map(source,List.of(
                cell("row1","netRevenue","999"),cell("row2","cogs","50"))));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_fact.fact_assertion
            WHERE organization_id=?
            """,Long.class,source.org()));
    }
    @Test void unsupportedFieldFailsBeforeAnySourcePublication() {
        var cells=List.of(cell("x","madeUpRevenue","100"));
        var source=approve("F02",1,cells);
        assertThrows(IllegalArgumentException.class,()->map(source,cells));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_fact.fact_assertion WHERE organization_id=?
            """,Long.class,source.org()));
    }
    @Test void mappedFactsCanBePublishedAndCompletelyCheckedThroughPostgres() {
        var cells=List.of(cell("pnl","netRevenue","100"),cell("pnl","cogs","65"));
        var source=approve("F02",1,cells);
        var mapped=map(source,cells);
        UUID revision=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at)
            VALUES (?,?,'APPROVED',now())
            """,revision,source.org());
        for(var entry:mapped){
            String hash=jdbc.queryForObject("SELECT fin_fact.fact_identity_hash(?)",
                    String.class,entry.factId());
            jdbc.update("""
                INSERT INTO fin_fact.fact_selection(
                    organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
                VALUES (?,?,?,?)
                """,source.org(),revision,hash,entry.factId());
        }
        publication.publish(new FactSetPublicationService.Request(source.org(),source.entity(),source.scope(),
                EconomicKernel.ReportingBasis.MANAGEMENT,AnalysisContext.DataSpace.SYNTHETIC,revision,null));
        var required=mapped.stream().map(m->new TrustedFinancialPopulationService.RequiredGroup(
                source.source(),m.metricCode(),m.start(),m.end())).toList();
        var verified=population.requireComplete(source.org(),revision,required);
        assertEquals(2,verified.rows().size());
        assertEquals(Set.of("netRevenue","cogs"),new HashSet<>(verified.rows().stream()
                .map(TrustedFinancialPopulationService.MappedFact::sourceField).toList()));
    }
}
