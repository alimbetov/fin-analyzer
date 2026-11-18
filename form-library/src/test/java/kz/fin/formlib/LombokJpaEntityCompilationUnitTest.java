package kz.fin.formlib;

import kz.fin.formlib.domain.FormValidationRule;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Compile-time proof that Maven invokes Lombok's Java 21 annotation processor. */
class LombokJpaEntityCompilationUnitTest {
  @Test
  void generatedAccessorsAreAvailableAndPreserveTextParameters() {
    var id = UUID.randomUUID();
    var version = UUID.randomUUID();
    var rule = new FormValidationRule();

    rule.setId(id);
    rule.setFormVersionId(version);
    rule.setRuleCode("RULE_01");
    rule.setRuleVersion("v1");
    rule.setRuleKind("THRESHOLD");
    rule.setTypedParams("{\"minimum\":100,\"maximum\":200}");
    rule.setSeverity("ERROR");

    assertEquals(id, rule.getId());
    assertEquals(version, rule.getFormVersionId());
    assertEquals("RULE_01", rule.getRuleCode());
    assertEquals("v1", rule.getRuleVersion());
    assertEquals("THRESHOLD", rule.getRuleKind());
    assertEquals("{\"minimum\":100,\"maximum\":200}", rule.getTypedParams());
    assertEquals("ERROR", rule.getSeverity());
  }
}
