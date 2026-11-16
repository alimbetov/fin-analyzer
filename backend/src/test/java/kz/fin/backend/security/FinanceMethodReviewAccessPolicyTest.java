package kz.fin.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FinanceMethodReviewAccessPolicyTest {
    private final FinanceMethodReviewAccessPolicy policy=new FinanceMethodReviewAccessPolicy();
    private Jwt token(String subject,String permission){
        return Jwt.withTokenValue("test-token")
            .header("alg","RS256").subject(subject)
            .issuedAt(Instant.parse("2026-11-01T00:00:00Z"))
            .expiresAt(Instant.parse("2026-12-01T00:00:00Z"))
            .claim("fin_method_access",List.of(
                Map.of("scope","FIN_PLATFORM","permissions",List.of(permission))))
            .build();
    }
    @Test void dedicatedReviewerCanReviewOtherAuthorButNotSelf(){
        var jwt=token("independent-financial-reviewer","METHOD_S2_REVIEW");
        var actor=policy.require(jwt,FinanceMethodReviewAccessPolicy.Action.REVIEW,"submitter");
        assertEquals("independent-financial-reviewer",actor.subject());
        assertThrows(AccessDeniedException.class,()->
            policy.require(jwt,FinanceMethodReviewAccessPolicy.Action.REVIEW,
                "independent-financial-reviewer"));
        assertThrows(AccessDeniedException.class,()->
            policy.require(jwt,FinanceMethodReviewAccessPolicy.Action.REVIEW,null));
    }
    @Test void submittingAndRevokingHaveDistinctPermissions(){
        var submitter=token("author","METHOD_SUBMIT");
        assertEquals("author",policy.require(submitter,
            FinanceMethodReviewAccessPolicy.Action.SUBMIT,"author").subject());
        assertThrows(AccessDeniedException.class,()->
            policy.require(submitter,FinanceMethodReviewAccessPolicy.Action.SUBMIT,"other"));
        assertThrows(AccessDeniedException.class,()->
            policy.require(submitter,FinanceMethodReviewAccessPolicy.Action.REVIEW,"other"));
        assertThrows(AccessDeniedException.class,()->
            policy.require(submitter,FinanceMethodReviewAccessPolicy.Action.REVOKE,"other"));
        assertEquals("supervisor",policy.require(token("supervisor","METHOD_S2_REVOKE"),
            FinanceMethodReviewAccessPolicy.Action.REVOKE,"author").subject());
    }
    @Test void ordinaryFinancialPermissionsAreNotMethodApproval(){
        Jwt jwt=Jwt.withTokenValue("token").header("alg","RS256").subject("reviewer")
            .claim("fin_access",List.of(Map.of("organizationId","00000000-0000-0000-0000-000000000001",
                "permissions",List.of("FIN_REVIEW")))).build();
        assertThrows(AccessDeniedException.class,()->
            policy.require(jwt,FinanceMethodReviewAccessPolicy.Action.REVIEW,"other"));
        assertThrows(AccessDeniedException.class,()->
            policy.require(null,FinanceMethodReviewAccessPolicy.Action.REVIEW,"other"));
        assertThrows(AccessDeniedException.class,()->
            policy.require(token("owner","METHOD_SUBMIT"),
                FinanceMethodReviewAccessPolicy.Action.REVIEW,"other"));
    }
    @Test void wrongScopeAndUnstructuredGrantFailClosed(){
        var jwt=Jwt.withTokenValue("token").header("alg","RS256").subject("reviewer")
            .claim("fin_method_access",List.of(
                Map.of("scope","TENANT","permissions",List.of("METHOD_S2_REVIEW")),
                Map.of("scope","FIN_PLATFORM","permissions","METHOD_S2_REVIEW"))).build();
        assertThrows(AccessDeniedException.class,()->
            policy.require(jwt,FinanceMethodReviewAccessPolicy.Action.REVIEW,"author"));
    }
}
