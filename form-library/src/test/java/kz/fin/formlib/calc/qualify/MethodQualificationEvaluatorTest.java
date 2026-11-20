package kz.fin.formlib.calc.qualify;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MethodQualificationEvaluatorTest {
  private static final String HASH = "a".repeat(64);
  private final MethodQualificationEvaluator gate = new MethodQualificationEvaluator();
  private MethodQualificationEvaluator.Request request(
      MethodQualificationEvaluator.QualificationClass c,
      boolean policy, boolean proof) {
    return new MethodQualificationEvaluator.Request(
        "C01", "C01.netReceivablesProposed", c, HASH,
        policy ? HASH : null, HASH, HASH, HASH,
        proof ? "documented clause" : null,
        proof ? "2026" : null,
        true,true,true,true,true,proof,true,true,policy);
  }
  @Test void classAArithmeticDoesNotBecomeFinancialApproval() {
    var d = gate.evaluate(request(MethodQualificationEvaluator.QualificationClass.A,true,true));
    assertEquals(MethodQualificationEvaluator.Status.FORMULA_VERIFIED,d.status());
    assertFalse(d.financiallyS2Approved());
    assertFalse(d.productionAdmitted());
  }
  @Test void standardEvidenceAndPolicyEnableOnlyMethodology() {
    var d = gate.evaluate(request(MethodQualificationEvaluator.QualificationClass.B,true,true));
    assertEquals(MethodQualificationEvaluator.Status.AUTO_QUALIFIED,d.status());
    assertFalse(d.financiallyS2Approved());
    assertFalse(d.productionAdmitted());
  }
  @Test void missingPolicyAlwaysBlocks() {
    var d = gate.evaluate(request(MethodQualificationEvaluator.QualificationClass.B,false,true));
    assertEquals(MethodQualificationEvaluator.Status.POLICY_INCOMPLETE,d.status());
    assertTrue(d.blockers().contains("POLICY_HASH_MISSING"));
  }
  @Test void missingStandardClauseBlocksClassB() {
    var d = gate.evaluate(request(MethodQualificationEvaluator.QualificationClass.B,true,false));
    assertEquals(MethodQualificationEvaluator.Status.EVIDENCE_INCOMPLETE,d.status());
  }
  @Test void estimatedMethodRequiresManualReview() {
    var d = gate.evaluate(request(MethodQualificationEvaluator.QualificationClass.D,true,true));
    assertEquals(MethodQualificationEvaluator.Status.MANUAL_REVIEW_REQUIRED,d.status());
  }
  @Test void wrongEvidenceHashCannotBeQualified() {
    var r = new MethodQualificationEvaluator.Request(
        "C02", "C02.totalOutstandingProposed",
        MethodQualificationEvaluator.QualificationClass.B,
        "not-a-sha",HASH,HASH,HASH,HASH,"clause","edition",
        true,true,true,true,true,true,true,true,true);
    var d=gate.evaluate(r);
    assertEquals(MethodQualificationEvaluator.Status.EVIDENCE_INCOMPLETE,d.status());
    assertFalse(d.productionAdmitted());
  }
  @Test void missingDuplicateGrainVerificationBlocks() {
    var r = new MethodQualificationEvaluator.Request(
        "C01","C01.netReceivablesProposed",
        MethodQualificationEvaluator.QualificationClass.B,
        HASH,HASH,HASH,HASH,HASH,"clause","edition",
        true,true,true,false,true,true,true,true,true);
    assertEquals(MethodQualificationEvaluator.Status.POLICY_INCOMPLETE,gate.evaluate(r).status());
  }
}
