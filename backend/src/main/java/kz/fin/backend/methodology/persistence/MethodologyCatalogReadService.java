package kz.fin.backend.methodology.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;

/**
 * Server read model: one methodology with proposed parameter names and the
 * current evidence status. Does not expose a public write route or executor.
 */
@Service
public class MethodologyCatalogReadService {
    private final MethodologyRepository repository;
    private final JdbcTemplate jdbc;

    public MethodologyCatalogReadService(MethodologyRepository repository, JdbcTemplate jdbc) {
        this.repository = Objects.requireNonNull(repository);
        this.jdbc = Objects.requireNonNull(jdbc);
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

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Methodology> allWithParameters() {
        // One evidence-aware status authority. fin_calc.method_family is an
        // immutable seed; returning its status after an engineering-evidence
        // event would disagree with fin_calc.methodology_catalog_view.
        Map<String, String> current = jdbc.query("""
                SELECT method_code, implementation_status
                  FROM fin_calc.methodology_catalog_view
                 ORDER BY method_code
                """, rs -> {
            var statuses = new TreeMap<String, String>();
            while (rs.next()) {
                if (statuses.putIfAbsent(rs.getString(1), rs.getString(2)) != null)
                    throw new IllegalStateException("FIN_METHOD_CATALOG_DUPLICATE_FAMILY");
            }
            return Map.copyOf(statuses);
        });
        var entities = repository.findAllWithParameterCandidates();
        if (current.size() != entities.size())
            throw new IllegalStateException("FIN_METHOD_CATALOG_STATUS_DRIFT");
        return entities.stream()
                .map(m -> {
                    String status = current.get(m.getMethodCode());
                    if (status == null)
                        throw new IllegalStateException("FIN_METHOD_CATALOG_STATUS_MISSING");
                    return new Methodology(m.getMethodCode(), m.getMethodGroup(),
                            m.getTitle(), status, m.getCatalogVersion(),
                            m.isReleaseQualified(), m.isAutoRunAllowed(),
                            m.getParameters().stream()
                                    .map(p -> new ParameterCandidate(p.getParameterCode(),
                                            p.getProposedType(), p.getReviewState(),
                                            p.getSourceRegistry())).toList());
                })
                .toList();
    }
}
