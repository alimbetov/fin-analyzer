package kz.fin.backend.methodology.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent parent of the 60 FIN HTAP business methodology families.
 *
 * The parameter candidates are DRAFT metadata; they do not give a method
 * an executable variant, trusted FactSet, financial S2 or ACTUAL permit.
 */
@Entity
@Immutable
@Table(schema = "fin_calc", name = "method_family")
public class MethodologyEntity {
    @Id
    @Column(name = "method_code", length = 32, nullable = false)
    private String methodCode;

    @Column(name = "method_group", length = 12, nullable = false)
    private String methodGroup;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "implementation_status", length = 80, nullable = false)
    private String implementationStatus;

    @Column(name = "catalog_version", length = 64, nullable = false)
    private String catalogVersion;

    @Column(name = "release_qualified", nullable = false)
    private boolean releaseQualified;

    @Column(name = "auto_run_allowed", nullable = false)
    private boolean autoRunAllowed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "methodology", fetch = FetchType.LAZY)
    @OrderBy("parameterCode ASC")
    private List<MethodParameterCandidateEntity> parameters = new ArrayList<>();

    @OneToMany(mappedBy = "methodology", fetch = FetchType.LAZY)
    @OrderBy("variantCode ASC, versionNo ASC")
    private List<MethodConfigVersionEntity> configurationVersions = new ArrayList<>();

    protected MethodologyEntity() { }

    public String getMethodCode() { return methodCode; }
    public String getMethodGroup() { return methodGroup; }
    public String getTitle() { return title; }
    public String getImplementationStatus() { return implementationStatus; }
    public String getCatalogVersion() { return catalogVersion; }
    public boolean isReleaseQualified() { return releaseQualified; }
    public boolean isAutoRunAllowed() { return autoRunAllowed; }
    public Instant getCreatedAt() { return createdAt; }

    public List<MethodParameterCandidateEntity> getParameters() {
        return List.copyOf(parameters);
    }

    public List<MethodConfigVersionEntity> getConfigurationVersions() {
        return List.copyOf(configurationVersions);
    }
}
