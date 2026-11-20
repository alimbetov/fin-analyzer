package kz.fin.formlib.calc.readiness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc154BatchTechnicalClassificationPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("calc154_batch_tech")
        .withUsername("fin").withPassword("test_only");
  @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired Calc154BatchTechnicalClassification batch;

  @Test void everyFormIsClassifiedWithoutInventedApproval() {
    var result=batch.classifyAll();
    assertEquals(154,result.forms().size());
    assertEquals(154,result.referenceOnly()+result.needsOutput()+result.hasCandidate());
    assertEquals(9,result.referenceOnly());
    assertEquals(64,result.catalogSha256().length());
    assertTrue(result.authoredCandidateCount()>=10);
    assertEquals(result.authoredCandidateCount(),result.forms().stream()
        .mapToInt(x->x.authoredCandidates().size()).sum());
    assertTrue(result.forms().stream().allMatch(f->!f.financiallyApproved()
        && f.blockers().contains("FINANCIAL_S2_NOT_APPROVED")));
    assertFalse(result.financiallyApproved());
  }
}
