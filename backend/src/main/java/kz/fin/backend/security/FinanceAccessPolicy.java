package kz.fin.backend.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Keycloak must emit a signed fin_access array through a restricted protocol
 * mapper. Never accept org, actor or permissions from request JSON.
 * A token with only ordinary realm roles grants NO financial API access.
 */
@Component
public class FinanceAccessPolicy {
    public enum Permission { FIN_SUBMIT, FIN_REVIEW, FIN_PUBLISH, FIN_READ, FIN_ANALYZE }
    public String subject(Jwt jwt) {
        if(jwt==null || jwt.getSubject()==null || jwt.getSubject().isBlank())
            throw new AccessDeniedException("FIN_VERIFIED_SUBJECT_REQUIRED");
        return jwt.getSubject();
    }
    public void require(Jwt jwt,UUID organization,Permission permission) {
        Objects.requireNonNull(organization);Objects.requireNonNull(permission);
        subject(jwt);
        Object raw=jwt.getClaims().get("fin_access");
        if(!(raw instanceof List<?> entries))
            throw new AccessDeniedException("FIN_ORGANIZATION_SCOPE_REQUIRED");
        for(Object entry:entries) {
            if(!(entry instanceof Map<?,?> claim))continue;
            if(!organization.toString().equals(claim.get("organizationId")))continue;
            if(!(claim.get("permissions") instanceof List<?> perms))continue;
            if(perms.contains(permission.name()))return;
        }
        throw new AccessDeniedException("FIN_ORGANIZATION_PERMISSION_DENIED");
    }
}
