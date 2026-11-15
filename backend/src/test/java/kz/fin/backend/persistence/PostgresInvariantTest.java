package kz.fin.backend.persistence;

import kz.fin.backend.domain.EconomicKernel;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class PostgresInvariantTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired FactAssertionJpaRepository repository;
    @Autowired JdbcTemplate jdbc;

    private EconomicKernel.FactAssertion fact(UUID org, UUID sourceId, UUID id) {
        var period = EconomicKernel.TemporalReference.interval(LocalDate.of(2026,1,1),LocalDate.of(2026,2,1));
        var key = new EconomicKernel.FactKey(org,UUID.randomUUID(), UUID.randomUUID(),
                "REVENUE","1.0.0",period,EconomicKernel.ReportingBasis.MANAGEMENT,
                "KZT",null,new TreeMap<>(Map.of("store","A")));
        return new EconomicKernel.FactAssertion(id,key,EconomicKernel.EconomicValue.present("100.25"),
                sourceId,Instant.now());
    }

    @Test void flywayMigrationAndJpaMappingWork() {
        UUID id=UUID.randomUUID();
        repository.saveAndFlush(FactAssertionJpaEntity.from(fact(UUID.randomUUID(),UUID.randomUUID(),id)));
        assertTrue(repository.findById(id).isPresent());
    }

    @Test void approvedFactsAreAppendOnlyAtDatabaseLevel() {
        UUID id=UUID.randomUUID();
        repository.saveAndFlush(FactAssertionJpaEntity.from(fact(UUID.randomUUID(),UUID.randomUUID(),id)));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("DELETE FROM fin_fact.fact_assertion WHERE id = ?", id));
        assertTrue(repository.findById(id).isPresent());
    }

    @Test void checkConstraintsRejectNonNumericStatesWithAmounts() {
        UUID id=UUID.randomUUID();
        repository.saveAndFlush(FactAssertionJpaEntity.from(fact(UUID.randomUUID(),UUID.randomUUID(),id)));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("INSERT INTO fin_fact.fact_assertion " +
                        "SELECT ?, organization_id, economic_entity_id, business_scope_id, metric_code, metric_version, " +
                        "time_kind, start_inclusive, end_exclusive, reporting_basis, unit_code, currency_code, " +
                        "dimension_hash, dimensions, 'MISSING', numeric_value, NULL, source_revision_id, recorded_at " +
                        "FROM fin_fact.fact_assertion WHERE id = ?", UUID.randomUUID(), id));
    }

    @Test void factSelectionCannotReferenceOtherOrganization() {
        UUID orgA=UUID.randomUUID(), orgB=UUID.randomUUID();
        UUID factId=UUID.randomUUID(), setId=UUID.randomUUID();
        repository.saveAndFlush(FactAssertionJpaEntity.from(fact(orgA,UUID.randomUUID(),factId)));
        jdbc.update("INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at) VALUES (?,?,?,now())",
                setId,orgB,"APPROVED");
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO fin_fact.fact_selection(organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id) VALUES (?,?,?,?)",
                orgB,setId,"hash",factId));
    }

    @Test void nullCurrencyDoesNotBypassUniqueSourceKey() {
        UUID id = UUID.randomUUID();
        repository.saveAndFlush(FactAssertionJpaEntity.from(fact(UUID.randomUUID(), UUID.randomUUID(), id)));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO fin_fact.fact_assertion " +
                "SELECT ?, organization_id, economic_entity_id, business_scope_id, metric_code, metric_version, " +
                "time_kind, start_inclusive, end_exclusive, reporting_basis, unit_code, currency_code, " +
                "dimension_hash, dimensions, value_state, numeric_value, estimate_method_ref, source_revision_id, recorded_at " +
                "FROM fin_fact.fact_assertion WHERE id = ?", UUID.randomUUID(), id));
    }

    @Test void estimateCannotLackMethodAtDatabaseLevel() {
        UUID id = UUID.randomUUID();
        repository.saveAndFlush(FactAssertionJpaEntity.from(fact(UUID.randomUUID(), UUID.randomUUID(), id)));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO fin_fact.fact_assertion " +
                "SELECT ?, organization_id, economic_entity_id, business_scope_id, metric_code, metric_version, " +
                "time_kind, start_inclusive, end_exclusive, reporting_basis, unit_code, currency_code, " +
                "dimension_hash, dimensions, 'ESTIMATED', numeric_value, NULL, source_revision_id, recorded_at " +
                "FROM fin_fact.fact_assertion WHERE id = ?", UUID.randomUUID(), id));
    }

    @Test void parentFactSetCannotBelongToAnotherOrganization() {
        UUID orgA = UUID.randomUUID(), orgB = UUID.randomUUID(), parentId = UUID.randomUUID();
        jdbc.update("INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at) VALUES (?,?,?,now())",
                parentId, orgA, "APPROVED");
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO fin_fact.fact_set_revision(id,organization_id,parent_revision_id,status,approved_at) " +
                "VALUES (?,?,?,?,now())", UUID.randomUUID(), orgB, parentId, "APPROVED"));
    }

    @Test void approvedFactSetCannotBeDeleted() {
        UUID org = UUID.randomUUID(), setId = UUID.randomUUID();
        jdbc.update("INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at) VALUES (?,?,?,now())",
                setId, org, "APPROVED");
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM fin_fact.fact_set_revision WHERE id = ?", setId));
    }



    private UUID smokeUuid(String label) {
        return UUID.nameUUIDFromBytes(("FIN-SMOKE:"+label).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void smokeFixturePersistsAllSourcesPeriodsEntitiesAndAmounts() throws Exception {
        var stream = getClass().getResourceAsStream("/smoke/postgres-facts.tsv");
        assertNotNull(stream);
        java.util.List<EconomicKernel.FactAssertion> facts = new java.util.ArrayList<>();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
            assertEquals("org\tsourceRevision\tmetric\ttimeKind\tstart\tend\tstore\tunit\tcurrency\tvalue",
                    reader.readLine());
            String row;
            while ((row=reader.readLine())!=null) {
                if (row.isBlank()) continue;
                String[] columns=row.split("\\t",-1);
                assertEquals(10,columns.length,row);
                String org=columns[0], source=columns[1], metric=columns[2], store=columns[6];
                UUID organization=smokeUuid(org);
                var time=new EconomicKernel.TemporalReference(
                        EconomicKernel.TimeKind.valueOf(columns[3]),
                        LocalDate.parse(columns[4]),LocalDate.parse(columns[5]));
                var key=new EconomicKernel.FactKey(
                        organization,smokeUuid(org+":ENTITY"),smokeUuid(org+":SCOPE"),
                        metric,"smoke-v1",time,EconomicKernel.ReportingBasis.MANAGEMENT,
                        columns[7],columns[8].equals("-")?null:columns[8],
                        new java.util.TreeMap<>(java.util.Map.of("store",store)));
                var value=columns[9].equals("0!")
                        ? EconomicKernel.EconomicValue.explicitZero()
                        : EconomicKernel.EconomicValue.present(columns[9]);
                UUID id=smokeUuid(String.join("|",columns));
                facts.add(new EconomicKernel.FactAssertion(
                        id,key,value,smokeUuid(org+":"+source),Instant.parse("2026-11-15T00:00:00Z")));
            }
        }
        assertEquals(16,facts.size());
        for(var f : facts) repository.saveAndFlush(FactAssertionJpaEntity.from(f));

        UUID orgA=smokeUuid("ORG_A"),orgB=smokeUuid("ORG_B");
        assertEquals(14L,jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_fact.fact_assertion WHERE organization_id = ?",Long.class,orgA));
        assertEquals(2L,jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_fact.fact_assertion WHERE organization_id = ?",Long.class,orgB));

        // 3 independent assertions for the same business/month/metric, including a restatement.
        assertEquals(3L,jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_fact.fact_assertion WHERE organization_id = ? " +
                "AND metric_code = 'REV_NET' AND start_inclusive = DATE '2026-01-01' " +
                "AND dimensions ->> 'store' = 'NORTH'",
                Long.class,orgA));

        // Four different values remain distinct; do not silently sum or select latest by timestamp.
        var competing=jdbc.queryForList(
                "SELECT numeric_value FROM fin_fact.fact_assertion WHERE organization_id = ? " +
                "AND metric_code = 'REV_NET' AND start_inclusive = DATE '2026-01-01' " +
                "AND dimensions ->> 'store' = 'NORTH' ORDER BY numeric_value",
                java.math.BigDecimal.class,orgA);
        assertEquals(3,competing.size());
        assertEquals(0,competing.get(0).compareTo(new java.math.BigDecimal("980")));
        assertEquals(0,competing.get(1).compareTo(new java.math.BigDecimal("1000")));
        assertEquals(0,competing.get(2).compareTo(new java.math.BigDecimal("1050")));

        assertEquals(1L,jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_fact.fact_assertion WHERE organization_id = ? " +
                "AND metric_code = 'SOLD_QTY' AND currency_code IS NULL AND value_state = 'EXPLICIT_ZERO'",
                Long.class,orgA));

        // One logical approved key, two immutable revision snapshots, distinct selected sources.
        var original=facts.stream().filter(f -> f.key().organizationId().equals(orgA)
                && f.key().metricCode().equals("REV_NET")
                && f.key().time().startInclusive().equals(LocalDate.of(2026,1,1))
                && f.key().dimensions().get("store").equals("NORTH")
                && f.sourceRevisionId().equals(smokeUuid("ORG_A:ERP_R1"))).findFirst().orElseThrow();
        var corrected=facts.stream().filter(f -> f.key().organizationId().equals(orgA)
                && f.key().metricCode().equals("REV_NET")
                && f.key().time().startInclusive().equals(LocalDate.of(2026,1,1))
                && f.key().dimensions().get("store").equals("NORTH")
                && f.sourceRevisionId().equals(smokeUuid("ORG_A:RESTATEMENT_R2"))).findFirst().orElseThrow();

        UUID oldSet=UUID.randomUUID(),newSet=UUID.randomUUID();
        jdbc.update("INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at) VALUES(?,?,?,now())",
                oldSet,orgA,"APPROVED");
        jdbc.update("INSERT INTO fin_fact.fact_selection(organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id) " +
                "VALUES(?,?,?,?)",orgA,oldSet,"smoke-revenue-jan-north",original.id());
        jdbc.update("INSERT INTO fin_fact.fact_set_revision(id,organization_id,parent_revision_id,status,approved_at) " +
                "VALUES(?,?,?,?,now())",newSet,orgA,oldSet,"APPROVED");
        jdbc.update("INSERT INTO fin_fact.fact_selection(organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id) " +
                "VALUES(?,?,?,?)",orgA,newSet,"smoke-revenue-jan-north",corrected.id());

        assertEquals(original.id(),jdbc.queryForObject(
                "SELECT fact_assertion_id FROM fin_fact.fact_selection WHERE fact_set_revision_id = ?",
                UUID.class,oldSet));
        assertEquals(corrected.id(),jdbc.queryForObject(
                "SELECT fact_assertion_id FROM fin_fact.fact_selection WHERE fact_set_revision_id = ?",
                UUID.class,newSet));
        assertEquals(0,jdbc.queryForObject(
                "SELECT a.numeric_value FROM fin_fact.fact_selection s " +
                "JOIN fin_fact.fact_assertion a ON a.id = s.fact_assertion_id " +
                "WHERE s.fact_set_revision_id = ?",java.math.BigDecimal.class,oldSet)
                .compareTo(new java.math.BigDecimal("1000")));
        assertEquals(0,jdbc.queryForObject(
                "SELECT a.numeric_value FROM fin_fact.fact_selection s " +
                "JOIN fin_fact.fact_assertion a ON a.id = s.fact_assertion_id " +
                "WHERE s.fact_set_revision_id = ?",java.math.BigDecimal.class,newSet)
                .compareTo(new java.math.BigDecimal("1050")));
    }
}
