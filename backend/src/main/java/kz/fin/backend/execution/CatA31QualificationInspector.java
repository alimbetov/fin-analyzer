package kz.fin.backend.execution;

import kz.fin.backend.persistence.TrustedFinancialPopulationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * CAT-A3.1 standalone verification composition. The result is advisory for a
 * future server-owned executor adapter, never an execution permit.
 * Required groups MUST originate from a signed server-side method definition,
 * not a client payload. No HTTP handler exposes this class.
 */
@Service
public final class CatA31QualificationInspector {
    public enum Status { INVALID_REQUEST, PUBLICATION_REJECTED, METHOD_S2_HOLD,
        SOURCE_REVIEW_OR_POPULATION_REJECTED, REVIEWED_INPUTS_RELEASE_HOLD }
    public record Decision(Status status,String reasonCode) {
        public boolean permitsExecution() { return false; }
    }

    private final CatA3MethodFactAdmission publication;
    private final CatA31MethodReviewRegistry reviews;
    private final TrustedFinancialPopulationService population;

    public CatA31QualificationInspector(CatA3MethodFactAdmission publication,
                                         CatA31MethodReviewRegistry reviews,
                                         TrustedFinancialPopulationService population) {
        this.publication=Objects.requireNonNull(publication);
        this.reviews=Objects.requireNonNull(reviews);
        this.population=Objects.requireNonNull(population);
    }

    public Decision inspect(CatA31MethodReviewRegistry.Key method,
                            MethodTaskAdapter.TrustedRevision revision,
                            List<TrustedFinancialPopulationService.RequiredGroup> requiredGroups) {
        if(method==null || revision==null || requiredGroups==null ||
                requiredGroups.isEmpty() ||
                requiredGroups.stream().anyMatch(Objects::isNull) ||
                requiredGroups.size()!=requiredGroups.stream().distinct().count() ||
                !"ACTUAL".equals(revision.dataSpace()))
            return new Decision(Status.INVALID_REQUEST,"CAT_A31_INVALID_SCOPE_OR_GROUPS");

        var publicationDecision=publication.inspect(method.methodCode(),method.formCode(),revision);
        if(publicationDecision.status()!=CatA3MethodFactAdmission.Status.METHOD_S2_HOLD)
            return new Decision(Status.PUBLICATION_REJECTED,publicationDecision.status().name());

        var methodReview=reviews.lookup(method);
        if(!methodReview.independentlyReviewed())
            return new Decision(Status.METHOD_S2_HOLD,methodReview.status().name());

        try {
            var checked=population.requireReviewedActual(revision.organizationId(),
                    revision.factSetRevisionId(),List.copyOf(requiredGroups));
            if(checked.rows().isEmpty() ||
                    !revision.factSetRevisionId().equals(checked.factSetRevisionId()))
                return new Decision(Status.SOURCE_REVIEW_OR_POPULATION_REJECTED,
                        "CAT_A31_NO_PINNED_APPROVED_FACTS");
        } catch(RuntimeException error) {
            // Reject incomplete/foreign/revoked review populations without
            // returning sensitive SQL, field or tenant details to a client.
            return new Decision(Status.SOURCE_REVIEW_OR_POPULATION_REJECTED,
                    "CAT_A31_SOURCE_REVIEW_REQUIRED");
        }
        // Even a correctly reviewed fact set cannot currently be executed:
        // signed method binding to the exact required groups and executor IAM
        // have not passed independent acceptance.
        return new Decision(Status.REVIEWED_INPUTS_RELEASE_HOLD,"CAT_A31_EXECUTOR_GATE_NOT_QUALIFIED");
    }
}
