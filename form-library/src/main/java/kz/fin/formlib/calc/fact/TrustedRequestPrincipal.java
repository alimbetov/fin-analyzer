package kz.fin.formlib.calc.fact;

import java.util.Set;

/**
 * Server-side verified IAM/ABAC principal. Deliberately no public constructor,
 * setters or JSON creator. The backend has NO IAM adapter yet, hence runtime
 * cannot create an authorized production principal from a client request.
 */
public final class TrustedRequestPrincipal {
  public static final String QUALIFY_ACTION = "FIN_FACT_QUALIFY";
  private final String subjectId;
  private final String identityProvider;
  private final FactScope permittedScope;
  private final String expectedNonce;
  private final Set<String> actions;

  TrustedRequestPrincipal(String subjectId,String identityProvider,
      FactScope permittedScope,String expectedNonce,Set<String> actions) {
    FactDigest.required(subjectId,"IAM_SUBJECT_REQUIRED");
    FactDigest.required(identityProvider,"IAM_PROVIDER_REQUIRED");
    if (permittedScope == null || expectedNonce == null || expectedNonce.length()<16
        || actions == null)throw new IllegalArgumentException("IAM_PRINCIPAL_INVALID");
    this.subjectId=subjectId;
    this.identityProvider=identityProvider;
    this.permittedScope=permittedScope;
    this.expectedNonce=expectedNonce;
    this.actions=Set.copyOf(actions);
  }

  public String subjectId(){return subjectId;}
  public String identityProvider(){return identityProvider;}
  public FactScope permittedScope(){return permittedScope;}
  public String expectedNonce(){return expectedNonce;}
  public boolean permits(String action){return actions.contains(action);}
}
