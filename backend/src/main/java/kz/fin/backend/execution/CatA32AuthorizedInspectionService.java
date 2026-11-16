package kz.fin.backend.execution;

import kz.fin.backend.persistence.TrustedFinancialPopulationService;
import kz.fin.backend.security.FinanceAccessPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * CAT-A3.2: authenticated, organization-scoped inspection facade.
 * Not an HTTP endpoint and not an execution permit. The actual S2 reviewer
 * uses FinanceMethodReviewAccessPolicy, not FIN_ANALYZE.
 */
@Service
public final class CatA32AuthorizedInspectionService {
    private final FinanceAccessPolicy access;
    private final CatA31QualificationInspector inspector;

    public CatA32AuthorizedInspectionService(FinanceAccessPolicy access,
                                               CatA31QualificationInspector inspector) {
        this.access=Objects.requireNonNull(access);
        this.inspector=Objects.requireNonNull(inspector);
    }
    /**
     * Only a server-selected revision and server-owned requirement definition
     * should reach this method. Never expose requiredGroups to user-supplied
     * requests without binding them to the S2-reviewed method version.
     */
    public CatA31QualificationInspector.Decision inspect(
            Jwt authenticatedJwt,
            CatA31MethodReviewRegistry.Key method,
            MethodTaskAdapter.TrustedRevision revision,
            List<TrustedFinancialPopulationService.RequiredGroup> serverRequirements) {
        Objects.requireNonNull(revision,"REVISION_REQUIRED");
        access.require(authenticatedJwt,revision.organizationId(),
                FinanceAccessPolicy.Permission.FIN_ANALYZE);
        return inspector.inspect(method,revision,serverRequirements);
    }
}
