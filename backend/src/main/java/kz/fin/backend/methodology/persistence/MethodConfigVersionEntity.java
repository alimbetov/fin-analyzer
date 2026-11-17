package kz.fin.backend.methodology.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable *actual configuration version*, in contrast to proposed
 * parameter names. Moderation/activation/financial S2 remain separate.
 * Hashes are generated and verified by PostgreSQL at creation.
 */
@Entity
@Immutable
@Table(schema = "fin_calc", name = "method_config_version")
public class MethodConfigVersionEntity {
    @Id
    @Column(name = "config_id", nullable = false)
    private UUID id;

    @Column(name = "method_code", length = 32, nullable = false)
    private String methodCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "method_code", referencedColumnName = "method_code",
                insertable = false, updatable = false, nullable = false)
    private MethodologyEntity methodology;

    @Column(name = "variant_code", length = 128, nullable = false)
    private String variantCode;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "parent_config_id")
    private UUID parentConfigId;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "engine", length = 8, nullable = false)
    private String engine;

    @Column(name = "algorithm_id", length = 128, nullable = false)
    private String algorithmId;

    @Column(name = "algorithm_version", length = 64, nullable = false)
    private String algorithmVersion;

    // V20261117_02 stores SHA-256 digests as PostgreSQL CHAR(64) (bpchar).
    // Keep Hibernate JDBC metadata aligned with Flyway rather than altering historic migrations.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "algorithm_sha256", length = 64, columnDefinition = "char(64)", nullable = false)
    private String algorithmSha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> parameters;

    // V20261117_02 stores SHA-256 digests as PostgreSQL CHAR(64) (bpchar).
    // Keep Hibernate JDBC metadata aligned with Flyway rather than altering historic migrations.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "params_sha256", length = 64, columnDefinition = "char(64)", nullable = false)
    private String paramsSha256;

    // V20261117_02 stores SHA-256 digests as PostgreSQL CHAR(64) (bpchar).
    // Keep Hibernate JDBC metadata aligned with Flyway rather than altering historic migrations.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "effective_sha256", length = 64, columnDefinition = "char(64)", nullable = false)
    private String effectiveSha256;

    @Column(name = "hash_contract_version", length = 32, nullable = false)
    private String hashContractVersion;

    @Column(name = "created_by", length = 200, nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MethodConfigVersionEntity() { }

    public UUID getId() { return id; }
    public String getMethodCode() { return methodCode; }
    public String getVariantCode() { return variantCode; }
    public int getVersionNo() { return versionNo; }
    public UUID getParentConfigId() { return parentConfigId; }
    public UUID getContractId() { return contractId; }
    public String getEngine() { return engine; }
    public String getAlgorithmId() { return algorithmId; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public String getAlgorithmSha256() { return algorithmSha256; }
    public Map<String, Object> getParameters() {
        return Map.copyOf(parameters);
    }
    public String getParamsSha256() { return paramsSha256; }
    public String getEffectiveSha256() { return effectiveSha256; }
    public String getHashContractVersion() { return hashContractVersion; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
