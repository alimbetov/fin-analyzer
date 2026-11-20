package kz.fin.formlib.calc.qualify;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AUTOQUAL-1.B: fail-closed, per-output methodology gate. No financial S2
 * approval or production admission can be created through this service.
 */
@Service
public final class MethodQualificationEvaluator {
  private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
  public enum QualificationClass { A, B, C, D, E }
  public enum Status { FORMULA_VERIFIED, POLICY_INCOMPLETE, EVIDENCE_INCOMPLETE,
    MANUAL_REVIEW_REQUIRED, AUTO_QUALIFIED }
  public record Request(
      String formCode, String outputCode, QualificationClass classification,
      String formulaSha256, String policySha256, String artifactSha256,
      String factSetSha256, String testEvidenceSha256,
      String standardReference, String standardEdition,
      boolean exactInputBindingsVerified, boolean requiredFieldsPresent,
      boolean periodAndCurrencyVerified, boolean uniqueGrainVerified,
      boolean signAndRoundingPolicyVerified, boolean normativeApplicabilityVerified,
      boolean sqlJavaParityPassed, boolean negativeControlsPassed,
      boolean independentPolicyApproved) {}
  public record Decision(Status status, List<String> blockers,
      boolean financiallyS2Approved, boolean productionAdmitted) {
    public Decision { blockers = List.copyOf(blockers); }
  }

  public Decision evaluate(Request r) {
    Objects.requireNonNull(r, "qualification request required");
    var blockers = new ArrayList<String>();
    if (r.classification() == null || blank(r.formCode()) || blank(r.outputCode())
        || !r.outputCode().startsWith(r.formCode() + "."))
      blockers.add("INVALID_METHOD_IDENTITY");
    if (!sha(r.formulaSha256()) || !sha(r.artifactSha256())
        || !sha(r.testEvidenceSha256()) || !sha(r.factSetSha256()))
      blockers.add("MISSING_OR_INVALID_EVIDENCE_SHA");
    if (!r.exactInputBindingsVerified()) blockers.add("INPUT_BINDINGS_UNVERIFIED");
    if (!r.requiredFieldsPresent()) blockers.add("REQUIRED_FIELD_MISSING");
    if (!r.sqlJavaParityPassed()) blockers.add("SQL_JAVA_PARITY_MISSING");
    if (!r.negativeControlsPassed()) blockers.add("NEGATIVE_CONTROLS_MISSING");
    if (!blockers.isEmpty())
      return new Decision(Status.EVIDENCE_INCOMPLETE, blockers, false, false);

    // Arithmetic verified does not imply that the accounting meaning is verified.
    if (r.classification() == QualificationClass.D ||
        r.classification() == QualificationClass.E)
      return new Decision(Status.MANUAL_REVIEW_REQUIRED,
          List.of("ESTIMATION_OR_NONSTANDARD_METHODOLOGY"), false, false);

    if (!sha(r.policySha256()) || !r.independentPolicyApproved()
        || !r.periodAndCurrencyVerified() || !r.uniqueGrainVerified()
        || !r.signAndRoundingPolicyVerified()) {
      if (!sha(r.policySha256())) blockers.add("POLICY_HASH_MISSING");
      if (!r.independentPolicyApproved()) blockers.add("POLICY_NOT_APPROVED");
      if (!r.periodAndCurrencyVerified()) blockers.add("PERIOD_CURRENCY_UNVERIFIED");
      if (!r.uniqueGrainVerified()) blockers.add("DUPLICATE_GRAIN_UNVERIFIED");
      if (!r.signAndRoundingPolicyVerified()) blockers.add("SIGN_ROUNDING_UNVERIFIED");
      return new Decision(Status.POLICY_INCOMPLETE, blockers, false, false);
    }
    if (r.classification() == QualificationClass.B ||
        r.classification() == QualificationClass.C) {
      if (blank(r.standardReference()) || blank(r.standardEdition())
          || !r.normativeApplicabilityVerified())
        return new Decision(Status.EVIDENCE_INCOMPLETE,
            List.of("STANDARD_REFERENCE_OR_APPLICABILITY_UNVERIFIED"), false, false);
    }
    // Generic arithmetic of class A is verified, but never labeled standards-qualified.
    if (r.classification() == QualificationClass.A)
      return new Decision(Status.FORMULA_VERIFIED, List.of(), false, false);
    return new Decision(Status.AUTO_QUALIFIED, List.of(), false, false);
  }
  private static boolean sha(String s) { return s != null && SHA.matcher(s).matches(); }
  private static boolean blank(String s) { return s == null || s.isBlank(); }
}
