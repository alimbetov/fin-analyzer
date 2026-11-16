package kz.fin.backend.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CAT-A3.2: signed Keycloak JWT identity/permission gate for future S2 write APIs.
 * The class is NOT a review-submission API and grants no database write.
 *
 * A dedicated Keycloak protocol mapper must emit fin_method_access in the
 * validated JWT. Mere realm role names, arbitrary HTTP headers, request JSON,
 * and FIN_REVIEW organization permissions do not grant methodology S2 approval.
 */
@Component
public final class FinanceMethodReviewAccessPolicy {
    public enum Action { SUBMIT, REVIEW, REVOKE }
    public record VerifiedActor(String subject,Action action) {}

    public VerifiedActor require(Jwt jwt,Action action,String submittedBy) {
        Objects.requireNonNull(action);
        if(jwt==null || jwt.getSubject()==null || jwt.getSubject().isBlank())
            throw new AccessDeniedException("FIN_METHOD_AUTHENTICATED_SUBJECT_REQUIRED");
        // JWT validation (issuer, audience, signature and expiry) belongs to
        // FinancialApiSecurity; never parse unsigned claims at this boundary.
        Object raw=jwt.getClaims().get("fin_method_access");
        if(!(raw instanceof List<?> entries))
            throw new AccessDeniedException("FIN_METHOD_REVIEW_SCOPE_REQUIRED");
        String required=switch(action){
            case SUBMIT -> "METHOD_SUBMIT";
            case REVIEW -> "METHOD_S2_REVIEW";
            case REVOKE -> "METHOD_S2_REVOKE";
        };
        boolean matched=false;
        for(Object entry:entries) {
            if(!(entry instanceof Map<?,?> grant))continue;
            if(!"FIN_PLATFORM".equals(grant.get("scope")))continue;
            Object actions=grant.get("permissions");
            if(!(actions instanceof List<?> perms) || !perms.contains(required))continue;
            // Review and revocation must be independently authorized.
            // Role decisions remain restricted to a dedicated signed claim.
            matched=true;
            break;
        }
        if(!matched)throw new AccessDeniedException("FIN_METHOD_REVIEW_PERMISSION_DENIED");
        if(action==Action.REVIEW &&
                (submittedBy==null || submittedBy.isBlank() || submittedBy.equals(jwt.getSubject())))
            throw new AccessDeniedException("FIN_METHOD_SELF_REVIEW_FORBIDDEN");
        if(action==Action.SUBMIT && submittedBy!=null &&
                !submittedBy.equals(jwt.getSubject()))
            throw new AccessDeniedException("FIN_METHOD_SUBMITTER_SPOOFED");
        return new VerifiedActor(jwt.getSubject(),action);
    }
}
