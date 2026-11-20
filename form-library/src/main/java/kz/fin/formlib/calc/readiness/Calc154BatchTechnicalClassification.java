package kz.fin.formlib.calc.readiness;

import kz.fin.formlib.calc.plan.Calc154CatalogWidePlanner;
import kz.fin.formlib.calc.plan.Calc154CatalogWidePlanner.FormPlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * CALC-154 classification across all real version-pinned forms.
 * Does not invent a formula/IFRS clause; preparation output is UAT/editorial.
 */
@Service
public class Calc154BatchTechnicalClassification {
  public enum Readiness {
    REFERENCE_ONLY_NEEDS_SCOPE_DECISION,
    NUMERIC_FORM_NEEDS_OUTPUT_CONTRACT,
    CANDIDATE_NEEDS_DOMAIN_POLICY
  }
  public record Candidate(String metricCode,String operation,String formulaVersion,
      String bindingSha256,String technicalKind) {}
  public record FormClassification(String formCode,String formVersionId,
      String schemaSha256,int directNumericFields,Readiness readiness,
      List<Candidate> authoredCandidates,List<String> blockers,
      String manifestSha256,boolean financiallyApproved) {
    public FormClassification {
      authoredCandidates=List.copyOf(authoredCandidates);
      blockers=List.copyOf(blockers);
    }
  }
  public record Batch(List<FormClassification> forms,int referenceOnly,int needsOutput,
      int hasCandidate,int authoredCandidateCount,String catalogSha256,
      boolean financiallyApproved) {
    public Batch { forms=List.copyOf(forms); }
  }
  private final Calc154CatalogWidePlanner planner;
  public Calc154BatchTechnicalClassification(Calc154CatalogWidePlanner planner) {
    this.planner=Objects.requireNonNull(planner);
  }
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Batch classifyAll() {
    var inventory=planner.inventory();
    if(inventory.forms().size()!=154)
      throw new IllegalStateException("CALC154_CLASSIFICATION_INVENTORY_INCOMPLETE");
    var results=new ArrayList<FormClassification>();
    int reference=0,needs=0,candidates=0,count=0;
    for(FormPlan f:inventory.forms()) {
      Readiness state;
      if(f.directNumericFields()==0) {
        reference++;
        state=Readiness.REFERENCE_ONLY_NEEDS_SCOPE_DECISION;
      } else if(f.candidates().isEmpty()) {
        needs++;
        state=Readiness.NUMERIC_FORM_NEEDS_OUTPUT_CONTRACT;
      } else {
        candidates++;
        state=Readiness.CANDIDATE_NEEDS_DOMAIN_POLICY;
      }
      var authored=new ArrayList<Candidate>();
      for(var formula:f.candidates()) {
        String technicalKind=switch(formula.operation()) {
          case "SUBTRACT","RATIO","RECONCILE","ROLL_FORWARD","GROUP_BY",
            "RATIO_OF_SUMS"->"GENERIC_ARITHMETIC_CANDIDATE";
          default->"UNSUPPORTED_OPERATOR_REVIEW_REQUIRED";
        };
        authored.add(new Candidate(formula.outputMetric(),formula.operation(),
            formula.formulaVersion(),formula.bindingSha256(),technicalKind));
        count++;
      }
      var blockers=new ArrayList<String>();
      blockers.add("FINANCIAL_S2_NOT_APPROVED");
      blockers.add("STANDARD_AND_ACCOUNTING_POLICY_NOT_VERIFIED");
      if(state==Readiness.REFERENCE_ONLY_NEEDS_SCOPE_DECISION)
        blockers.add("NO_DIRECT_NUMERIC_SOURCE_SCOPE_REVIEW_REQUIRED");
      if(state==Readiness.NUMERIC_FORM_NEEDS_OUTPUT_CONTRACT)
        blockers.add("REQUIRED_AND_OPTIONAL_OUTPUTS_NOT_AUTHORED");
      if(state==Readiness.CANDIDATE_NEEDS_DOMAIN_POLICY)
        blockers.add("CANDIDATE_FORMULAS_UNQUALIFIED");
      results.add(new FormClassification(f.formCode(),f.formVersionId().toString(),
          f.schemaSha256(),f.directNumericFields(),state,authored,blockers,
          f.manifestSha256(),false));
    }
    if(reference+needs+candidates!=154 || count!=inventory.candidateFormulaCount())
      throw new IllegalStateException("CALC154_CLASSIFICATION_TOTALS_DRIFT");
    return new Batch(results,reference,needs,candidates,count,
        inventory.inventorySha256(),false);
  }
}
