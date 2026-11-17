package kz.fin.backend.methodology.persistence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Server read model: one methodology with proposed parameter names and the
 * current evidence status. Does not expose a public write route or executor.
 */
@Service
public class MethodologyCatalogReadService {
    private final MethodologyRepository repository;

    public MethodologyCatalogReadService(MethodologyRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public record ParameterCandidate(String code, String proposedType,
                                     String reviewState, String sourceRegistry) { }

    public record Methodology(String code, String group, String title,
                              String implementationStatus, String catalogVersion,
                              boolean releaseQualified, boolean autoRunAllowed,
                              List<ParameterCandidate> parameters) {
        public Methodology {
            parameters = List.copyOf(parameters);
        }
    }

    @Transactional(readOnly = true)
    public List<Methodology> allWithParameters() {
        return repository.findAllWithParameterCandidates().stream()
                .map(m -> new Methodology(m.getMethodCode(), m.getMethodGroup(),
                        m.getTitle(), m.getImplementationStatus(),
                        m.getCatalogVersion(), m.isReleaseQualified(),
                        m.isAutoRunAllowed(), m.getParameters().stream()
                                .map(p -> new ParameterCandidate(p.getParameterCode(),
                                        p.getProposedType(), p.getReviewState(),
                                        p.getSourceRegistry())).toList()))
                .toList();
    }
}
