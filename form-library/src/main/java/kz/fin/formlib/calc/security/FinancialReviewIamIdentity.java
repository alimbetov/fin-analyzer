package kz.fin.formlib.calc.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * IAM principal extracted exclusively from Spring Security's verified JWT,
 * never from a request body, header, reviewerRef or test-only FactSet principal.
 * The HTTP resource-server validates RSA/EC signature, issuer, timestamp and
 * dedicated audience BEFORE constructing JwtAuthenticationToken.
 */
@Component
public final class FinancialReviewIamIdentity {
  public static final String AUDIENCE="fin-form-library/financial-methodology-review/v1";
  public static final String AUTHOR_ROLE="FIN_METHOD_AUTHOR";
  public static final String REVIEWER_ROLE="FIN_METHOD_REVIEWER";

  public record VerifiedUser(String issuer,String subject,String audience) {
    public VerifiedUser{
      Objects.requireNonNull(issuer);
      Objects.requireNonNull(subject);
      Objects.requireNonNull(audience);
      if(issuer.isBlank()||subject.isBlank()||audience.isBlank())
        throw new AccessDeniedException("CALC3C3_IAM_PRINCIPAL_INCOMPLETE");
    }
    public boolean sameIdentity(VerifiedUser other){
      return other!=null && issuer.equals(other.issuer()) && subject.equals(other.subject());
    }
  }

  private final String configuredIssuer;
  private final String configuredAudience;

  public FinancialReviewIamIdentity(
      @Value("${fin.financial-review.issuer:}") String configuredIssuer,
      @Value("${fin.financial-review.audience:fin-form-library/financial-methodology-review/v1}")
      String configuredAudience) {
    this.configuredIssuer=Objects.requireNonNull(configuredIssuer);
    this.configuredAudience=Objects.requireNonNull(configuredAudience);
  }

  public VerifiedUser requireAuthor(){
    return requireRole(AUTHOR_ROLE);
  }

  public VerifiedUser requireReviewer(){
    return requireRole(REVIEWER_ROLE);
  }

  private VerifiedUser requireRole(String role){
    Authentication auth=SecurityContextHolder.getContext().getAuthentication();
    if(!(auth instanceof JwtAuthenticationToken jwtAuth)||!jwtAuth.isAuthenticated())
      throw new AccessDeniedException("CALC3C3_VERIFIED_JWT_REQUIRED");
    var jwt=jwtAuth.getToken();
    Instant now=Instant.now();
    if(configuredIssuer.isBlank()||!AUDIENCE.equals(configuredAudience)
        ||jwt.getIssuer()==null||!configuredIssuer.equals(jwt.getIssuer().toString())
        ||jwt.getAudience()==null||!jwt.getAudience().contains(AUDIENCE)
        ||jwt.getSubject()==null||jwt.getSubject().isBlank()
        ||jwt.getExpiresAt()==null||!now.isBefore(jwt.getExpiresAt())
        ||jwt.getNotBefore()!=null&&now.isBefore(jwt.getNotBefore()))
      throw new AccessDeniedException("CALC3C3_IAM_ISSUER_AUDIENCE_OR_TIME");
    // Keycloak default realm roles: nested, signed realm_access.roles claim.
    // In particular never accept role from unverified JSON or query parameters.
    Object realm=jwt.getClaims().get("realm_access");
    Object roles=realm instanceof Map<?,?> m?m.get("roles"):null;
    if(!(roles instanceof Collection<?> c) || !c.contains(role))
      throw new AccessDeniedException("CALC3C3_IAM_ROLE_DENIED:"+role);
    return new VerifiedUser(jwt.getIssuer().toString(),jwt.getSubject(),AUDIENCE);
  }
}
