package kz.fin.formlib.calc.qualify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class MethodQualificationReceiptPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("autoqual1").withUsername("fin").withPassword("test_only");
  @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired MethodQualificationReceiptStore store;
  @Autowired JdbcTemplate sql;
  private static final String H="a".repeat(64);

  @Test void replayIsIdempotentAndReceiptCannotBeChangedOrPromoted() throws Exception {
    var req = new MethodQualificationEvaluator.Request(
        "C01","C01.netReceivablesProposed",
        MethodQualificationEvaluator.QualificationClass.B,
        H,H,H,H,H,"verified clause","edition",
        true,true,true,true,true,true,true,true,true);
    var first=store.evaluateAndRecord(req);
    var again=store.evaluateAndRecord(req);
    assertFalse(first.replayed());
    assertTrue(again.replayed());
    assertEquals(first.id(),again.id());
    assertEquals("AUTO_QUALIFIED",first.status());
    assertFalse(first.financialS2Approved());
    assertFalse(first.productionAdmitted());
    assertThrows(DataAccessException.class,()->sql.update("""
      UPDATE fin_calc.method_qualification_receipt
      SET financial_s2_approved=true WHERE id=?
      """,first.id()));
    assertThrows(DataAccessException.class,()->sql.update("""
      DELETE FROM fin_calc.method_qualification_receipt WHERE id=?
      """,first.id()));
  }
  @Test void noApprovalInReceiptForClassA() throws Exception {
    var req = new MethodQualificationEvaluator.Request(
        "C02","C02.totalOutstandingProposed",
        MethodQualificationEvaluator.QualificationClass.A,
        H,H,H,H,H,null,null,
        true,true,true,true,true,false,true,true,true);
    var r=store.evaluateAndRecord(req);
    assertEquals("FORMULA_VERIFIED",r.status());
    assertFalse(r.financialS2Approved());
    assertFalse(r.productionAdmitted());
  }
}
