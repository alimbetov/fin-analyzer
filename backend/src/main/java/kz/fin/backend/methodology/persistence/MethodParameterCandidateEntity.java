package kz.fin.backend.methodology.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * One of 259 proposed parameters of the 60 FIN HTAP methodology families.
 * Not equivalent to an approved MethodParameterContract.
 */
@Entity
@Immutable
@Table(schema = "fin_calc", name = "method_parameter_candidate",
       uniqueConstraints = @UniqueConstraint(name = "uq_fin_method_candidate",
           columnNames = { "method_code", "parameter_code" }))
public class MethodParameterCandidateEntity {
    @Id
    @Column(name = "candidate_id", nullable = false)
    private UUID id;

    @Column(name = "method_code", length = 32, nullable = false)
    private String methodCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "method_code", referencedColumnName = "method_code",
                insertable = false, updatable = false, nullable = false)
    private MethodologyEntity methodology;

    @Column(name = "parameter_code", length = 128, nullable = false)
    private String parameterCode;

    @Column(name = "source_registry", length = 80, nullable = false)
    private String sourceRegistry;

    @Column(name = "proposed_type", length = 32, nullable = false)
    private String proposedType;

    @Column(name = "review_state", length = 32, nullable = false)
    private String reviewState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MethodParameterCandidateEntity() { }

    public UUID getId() { return id; }
    public String getMethodCode() { return methodCode; }
    public String getParameterCode() { return parameterCode; }
    public String getSourceRegistry() { return sourceRegistry; }
    public String getProposedType() { return proposedType; }
    public String getReviewState() { return reviewState; }
    public Instant getCreatedAt() { return createdAt; }
}
