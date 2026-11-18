package kz.fin.formlibrary.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Optional qualified-policy candidate linked to an existing lib_form_version.
 * No mutable governance state or financial approval is exposed by this entity.
 */
@Entity
@Immutable
@Table(schema = "fin_lib", name = "lib_form_contract_policy")
public class FormContractPolicyEntity {
    @Id
    @Column(name = "form_version_id", nullable = false)
    private UUID formVersionId;

    @Column(name = "canonicalization_version", nullable = false)
    private Integer canonicalizationVersion;

    @Column(name = "source_descriptor_version", length = 128, nullable = false)
    private String sourceDescriptorVersion;

    @Column(name = "compatibility_class", length = 48, nullable = false)
    private String compatibilityClass;

    @Column(name = "governance_state", length = 32, nullable = false)
    private String governanceState;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected FormContractPolicyEntity() {}

    public UUID getFormVersionId() { return formVersionId; }
    public int getCanonicalizationVersion() { return canonicalizationVersion; }
    public String getSourceDescriptorVersion() { return sourceDescriptorVersion; }
    public String getCompatibilityClass() { return compatibilityClass; }
    public String getGovernanceState() { return governanceState; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
