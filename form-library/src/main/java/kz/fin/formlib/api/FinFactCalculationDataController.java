package kz.fin.formlib.api;

import kz.fin.formlib.calc.fact.FinFactCalculationDataReader;
import kz.fin.formlib.calc.security.FinFactDataSecurityConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only JWT-gated FIN fact data. Request user_id is never the principal;
 * it MUST match the signed access-token subject. The receipt further proves
 * that this user initiated the exact project's FactSet. No project-sharing
 * authorization is inferred from that receipt.
 */
@RestController
@RequestMapping("/internal/fin-fact/data")
@ConditionalOnProperty(name="fin.fact.data-reader.enabled",havingValue="true")
public class FinFactCalculationDataController {
  private static final String READ_ROLE="FIN_FACT_READ";
  private final FinFactCalculationDataReader reader;
  private final String issuer;

  public FinFactCalculationDataController(FinFactCalculationDataReader reader,
      @Value("$"+"{fin.fact.data-reader.issuer:}") String issuer){
    this.reader=reader;
    this.issuer=issuer;
  }
  private UUID verifiedUser(JwtAuthenticationToken authentication,UUID requested){
    if(authentication==null||!authentication.isAuthenticated()
        ||requested==null||issuer==null||issuer.isBlank())
      throw new AccessDeniedException("FIN_FACT_VERIFIED_JWT_REQUIRED");
    var jwt=authentication.getToken();
    Instant now=Instant.now();
    if(jwt.getIssuer()==null||!issuer.equals(jwt.getIssuer().toString())
        ||jwt.getAudience()==null||!jwt.getAudience().contains(
          FinFactDataSecurityConfiguration.AUDIENCE)
        ||jwt.getExpiresAt()==null||!now.isBefore(jwt.getExpiresAt())
        ||jwt.getNotBefore()!=null&&now.isBefore(jwt.getNotBefore()))
      throw new AccessDeniedException("FIN_FACT_JWT_ISSUER_AUDIENCE_OR_TIME");
    Object realm=jwt.getClaims().get("realm_access");
    Object roleNames=realm instanceof Map<?,?> m?m.get("roles"):null;
    if(!(roleNames instanceof Collection<?> roles)||!roles.contains(READ_ROLE))
      throw new AccessDeniedException("FIN_FACT_READ_ROLE_REQUIRED");
    UUID subject;
    try{subject=UUID.fromString(jwt.getSubject());}
    catch(RuntimeException exception){
      throw new AccessDeniedException("FIN_FACT_UUID_SUBJECT_REQUIRED");
    }
    if(!requested.equals(subject))
      throw new AccessDeniedException("FIN_FACT_REQUESTED_USER_MISMATCH");
    return subject;
  }

  @GetMapping("/resolve")
  public FinFactCalculationDataReader.Page resolve(
      JwtAuthenticationToken authentication,
      @RequestParam("user_id") UUID userId,
      @RequestParam("project_id") UUID projectId,
      @RequestParam(name="fact_set_id",required=false) UUID exactFactSetId,
      @RequestParam(name="page_size",defaultValue="25") int pageSize,
      @RequestParam(name="page_token",required=false) String pageToken) {
    UUID trusted=verifiedUser(authentication,userId);
    return reader.read(trusted,new FinFactCalculationDataReader.Query(
      userId,projectId,exactFactSetId,pageSize,pageToken));
  }
}
