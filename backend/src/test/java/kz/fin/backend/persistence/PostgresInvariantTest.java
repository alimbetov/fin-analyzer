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
}
