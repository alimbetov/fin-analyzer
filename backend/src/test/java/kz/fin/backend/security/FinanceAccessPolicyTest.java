package kz.fin.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceAccessPolicyTest {
    private static final FinanceAccessPolicy POLICY=new FinanceAccessPolicy();
    private static Jwt token(UUID org,List<String> permissions){
        return Jwt.withTokenValue("verified-test-only").header("alg","RS256")
                .subject("subject-issuer-unique").issuer("https://iam.example/realms/fin")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .audience(List.of("fin-analyzer-api"))
                .claim("fin_access",List.of(Map.of(
                        "organizationId",org.toString(),"permissions",permissions)))
                .build();
    }
    @Test void signedClaimBasedOrganizationPermissionCanAuthorizeNamedOperation(){
        UUID org=UUID.randomUUID();
        Jwt jwt=token(org,List.of("FIN_SUBMIT","FIN_READ"));
        assertDoesNotThrow(()->POLICY.require(jwt,org,FinanceAccessPolicy.Permission.FIN_SUBMIT));
        assertEquals("subject-issuer-unique",POLICY.subject(jwt));
        assertThrows(AccessDeniedException.class,
                ()->POLICY.require(jwt,org,FinanceAccessPolicy.Permission.FIN_REVIEW));
        assertThrows(AccessDeniedException.class,
                ()->POLICY.require(jwt,UUID.randomUUID(),FinanceAccessPolicy.Permission.FIN_READ));
    }
    @Test void ordinaryRolesOrMalformedOrganizationArrayCannotAuthorizeFinancialData(){
        UUID org=UUID.randomUUID();
        Jwt normal=Jwt.withTokenValue("test").header("alg","RS256").subject("someone")
                .claim("realm_access",Map.of("roles",List.of("PLATFORM_ADMIN"))).build();
        assertThrows(AccessDeniedException.class,
                ()->POLICY.require(normal,org,FinanceAccessPolicy.Permission.FIN_PUBLISH));
        Jwt invalid=Jwt.withTokenValue("test").header("alg","RS256").subject("someone")
                .claim("fin_access",List.of(Map.of("organizationId",org.toString(),
                        "permissions","FIN_PUBLISH"))).build();
        assertThrows(AccessDeniedException.class,
                ()->POLICY.require(invalid,org,FinanceAccessPolicy.Permission.FIN_PUBLISH));
        assertThrows(AccessDeniedException.class,
                ()->POLICY.require(null,org,FinanceAccessPolicy.Permission.FIN_READ));
    }
}
