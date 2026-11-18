package kz.fin.backend.formcontract.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * Immutable content/version identity. S1/S2 decisions and activation are
 * deliberately not stored here; separate authorization gates remain HOLD.
 */
@Entity
@Immutable
@Table(schema = "fin_form", name = "form_contract_version")
public class FormContractVersionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contract_version_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_code", nullable = false, updatable = false)
    private FormDefinitionEntity form;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "manifest_format_version", nullable = false)
    private int manifestFormatVersion;

    @Column(name = "source_descriptor_version", nullable = false, length = 128)
    private String sourceDescriptorVersion;

    @Column(name = "schema_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
    private String schemaSha256;

    @Column(name = "mapping_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
    private String mappingSha256;

    @Column(name = "presentation_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
    private String presentationSha256;

    @Column(name = "compatibility_class", nullable = false, length = 48)
    private String compatibilityClass;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FormContractVersionEntity() { }

    public Long getId() { return id; }
    public FormDefinitionEntity getForm() { return form; }
    public int getVersionNo() { return versionNo; }
    public int getManifestFormatVersion() { return manifestFormatVersion; }
    public String getSourceDescriptorVersion() { return sourceDescriptorVersion; }
    public String getSchemaSha256() { return schemaSha256; }
    public String getMappingSha256() { return mappingSha256; }
    public String getPresentationSha256() { return presentationSha256; }
    public String getCompatibilityClass() { return compatibilityClass; }
    public Instant getCreatedAt() { return createdAt; }
}
