package kz.fin.backend.persistence;

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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class TrustedFinancialPopulationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }

    private static final LocalDate FROM=LocalDate.of(2026,10,1);
    private static final LocalDate TO=LocalDate.of(2026,11,1);
    @Autowired JdbcTemplate jdbc;
    @Autowired FactSetPublicationService publication;
    @Autowired TrustedFinancialPopulationService population;

    private record Context(UUID org,UUID entity,UUID scope,UUID source,UUID revision,List<UUID> facts) {
        TrustedFinancialPopulationService.RequiredGroup group() {
            return new TrustedFinancialPopulationService.RequiredGroup(source,"REV_NET",FROM,TO);
        }
    }

    private Context setup(int count) { return setup(count,false); }

    private Context setup(int count,boolean mixedCurrency) {
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),scope=UUID.randomUUID();
        UUID source=UUID.randomUUID(),revision=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision
                 (organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?,?,'F02','FIN-PILOT-V1',now())
            """,org,source);
        List<UUID> facts=new ArrayList<>();
        for(int i=0;i<count;i++){
            UUID fact=UUID.randomUUID();
            facts.add(fact);
            BigDecimal amount=new BigDecimal(i==0?"100":"200");
            jdbc.update("""
                INSERT INTO fin_fact.fact_assertion(
                    id,organization_id,economic_entity_id,business_scope_id,metric_code,metric_version,
                    time_kind,start_inclusive,end_exclusive,reporting_basis,unit_code,currency_code,
                    dimension_hash,dimensions,value_state,numeric_value,estimate_method_ref,
                    source_revision_id,recorded_at
                ) VALUES (?,?,?,?,'REV_NET','1.0.0','INTERVAL',?,?,'MANAGEMENT','MONEY',?,
                    encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex'),
                    ?::jsonb,'PRESENT',?,NULL,?,now())
                """,fact,org,entity,scope,FROM,TO,mixedCurrency&&i==1?"USD":"KZT",
                "{\"sale\":\""+i+"\"}", "{\"sale\":\""+i+"\"}",amount,source);
            jdbc.update("""
                INSERT INTO fin_fact.fact_origin(
                    fact_assertion_id,organization_id,source_revision_id,source_row_id,source_field)
                VALUES (?,?,?,?,?)
                """,fact,org,source,"sale-"+i,"netRevenue");
        }
        jdbc.update("""
            INSERT INTO fin_fact.source_population_control(
                organization_id,source_revision_id,metric_code,time_start,time_end,
                expected_rows,expected_total,source_document_hash)
            VALUES (?,?,'REV_NET',?,?,?,?,?)
            """,org,source,FROM,TO,count,new BigDecimal(count==1?"100":"300"),"a".repeat(64));
        jdbc.update("""
            INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at)
            VALUES (?,?,'APPROVED',now())
            """,revision,org);
        return new Context(org,entity,scope,source,revision,facts);
    }

    private void select(Context c,int count) {
        for(UUID fact:c.facts().subList(0,count)){
            String key=jdbc.queryForObject("SELECT fin_fact.fact_identity_hash(?)",String.class,fact);
            jdbc.update("""
                INSERT INTO fin_fact.fact_selection
                   (organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
                VALUES (?,?,?,?)
                """,c.org(),c.revision(),key,fact);
        }
        publication.publish(new FactSetPublicationService.Request(
                c.org(),c.entity(),c.scope(),
                kz.fin.backend.domain.EconomicKernel.ReportingBasis.MANAGEMENT,
                kz.fin.backend.analytics.AnalysisContext.DataSpace.SYNTHETIC,
                c.revision(),null));
    }

    @Test void registeredButNotSelectedRowsMustBlockEvenWhenFactSetIsPublished() {
        var c=setup(2);
        select(c,1); // Old publication logic permits a valid *subset* of selected approved facts.
        var err=assertThrows(IllegalArgumentException.class,
                ()->population.requireComplete(c.org(),c.revision(),List.of(c.group())));
        assertEquals("SOURCE_POPULATION_INCOMPLETE_OR_UNMAPPED",err.getMessage());
    }

    @Test void allRegisteredApprovedRowsAreVerifiedWithExactSourceLineage() {
        var c=setup(2);
        select(c,2);
        var result=population.requireComplete(c.org(),c.revision(),List.of(c.group()));
        assertEquals(2,result.rows().size());
        assertEquals(List.of("sale-0","sale-1"),result.rows().stream()
                .map(TrustedFinancialPopulationService.MappedFact::sourceRowId).toList());
        assertTrue(result.rows().stream().allMatch(r->r.formCode().equals("F02")
                && r.formVersion().equals("FIN-PILOT-V1")
                && r.metricCode().equals("REV_NET")
                && r.sourceField().equals("netRevenue")
                && r.sourceRevisionId().equals(c.source())));
        assertEquals(0,result.rows().stream().map(TrustedFinancialPopulationService.MappedFact::value)
                .reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(new BigDecimal("300")));
    }

    @Test void aClientCannotSelfDeclareApprovalViaAnotherTenant() {
        var c=setup(1);
        select(c,1);
        assertThrows(IllegalArgumentException.class,
                ()->population.requireComplete(UUID.randomUUID(),c.revision(),List.of(c.group())));
    }

    @Test void noUnscopedPopulationRequirementsAndNoUnpublishedRevision() {
        var c=setup(1);
        assertThrows(IllegalArgumentException.class,
                ()->population.requireComplete(c.org(),c.revision(),List.of(c.group())));
        select(c,1);
        assertThrows(IllegalArgumentException.class,
                ()->population.requireComplete(c.org(),c.revision(),List.of()));
    }

    @Test void mixedCurrenciesFromOneSourceCannotBecomeAQualifiedMoneyTotal() {
        var c=setup(2,true);
        select(c,2);
        var error=assertThrows(IllegalArgumentException.class,
                ()->population.requireComplete(c.org(),c.revision(),List.of(c.group())));
        assertEquals("SOURCE_POPULATION_MIXED_CURRENCIES",error.getMessage());
    }

    @Test void registeredSourceControlCannotLieAboutRowCountOrTotal() {
        var c=setup(2);
        assertThrows(DataAccessException.class,()->jdbc.update("""
            INSERT INTO fin_fact.source_population_control(
                organization_id,source_revision_id,metric_code,time_start,time_end,
                expected_rows,expected_total,source_document_hash)
            VALUES (?,?,'REV_NET',?,?,?,?,?)
            """,c.org(),c.source(),FROM,TO,1,new BigDecimal("300"),"b".repeat(64)));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE fin_fact.source_population_control SET expected_rows=1
            WHERE organization_id=? AND source_revision_id=?
            """,c.org(),c.source()));
    }

    @Test void noAdditionalRowsCanBeAttachedAfterPopulationControlIsSealed() {
        var c=setup(1);
        UUID fact=c.facts().getFirst();
        assertThrows(DataAccessException.class,()->jdbc.update("""
            INSERT INTO fin_fact.fact_origin(
                fact_assertion_id,organization_id,source_revision_id,source_row_id,source_field)
            VALUES (?,?,?,?,?)
            """,fact,c.org(),c.source(),"hidden-row","netRevenue"));
    }

    @Test void onlyVersionedKnownFormFieldsMayMapToCanonicalMetric() {
        UUID org=UUID.randomUUID(), source=UUID.randomUUID(), id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision(
                organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?,?,'F01','FIN-PILOT-V1',now())
            """,org,source);
        jdbc.update("""
            INSERT INTO fin_fact.fact_assertion(
                id,organization_id,economic_entity_id,business_scope_id,metric_code,metric_version,
                time_kind,start_inclusive,end_exclusive,reporting_basis,unit_code,currency_code,
                dimension_hash,dimensions,value_state,numeric_value,estimate_method_ref,
                source_revision_id,recorded_at)
            VALUES (?,?,?,?,'REV_NET','1.0.0','INTERVAL',?,?,'MANAGEMENT','MONEY','KZT',
                ?,?::jsonb,'PRESENT',100,NULL,?,now())
            """,id,org,UUID.randomUUID(),UUID.randomUUID(),FROM,TO,"0".repeat(64),"{}",source);
        assertThrows(DataAccessException.class,()->jdbc.update("""
            INSERT INTO fin_fact.fact_origin(
                fact_assertion_id,organization_id,source_revision_id,source_row_id,source_field)
            VALUES (?,?,?,?,?)
            """,id,org,source,"fake-revenue","netRevenue"));
    }
}
