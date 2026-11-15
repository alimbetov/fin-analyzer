package kz.fin.backend.persistence;

import jakarta.persistence.*;
import kz.fin.backend.domain.EconomicKernel;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Entity
@Table(schema = "fin_fact", name = "fact_assertion")
public class FactAssertionJpaEntity {
    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "economic_entity_id", nullable = false)
    private UUID economicEntityId;

    @Column(name = "business_scope_id", nullable = false)
    private UUID businessScopeId;

    @Column(name = "metric_code", nullable = false, length = 128)
    private String metricCode;

    @Column(name = "metric_version", nullable = false, length = 64)
    private String metricVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_kind", nullable = false, length = 16)
    private EconomicKernel.TimeKind timeKind;

    @Column(name = "start_inclusive", nullable = false)
    private LocalDate startInclusive;

    @Column(name = "end_exclusive", nullable = false)
    private LocalDate endExclusive;

    @Enumerated(EnumType.STRING)
    @Column(name = "reporting_basis", nullable = false, length = 24)
    private EconomicKernel.ReportingBasis reportingBasis;

    @Column(name = "unit_code", nullable = false, length = 32)
    private String unitCode;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "dimension_hash", nullable = false, length = 64)
    private String dimensionHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dimensions", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> dimensions;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_state", nullable = false, length = 24)
    private EconomicKernel.ValueState valueState;

    @Column(name = "numeric_value", precision = 38, scale = 12)
    private BigDecimal numericValue;

    @Column(name = "estimate_method_ref", length = 200)
    private String estimateMethodRef;

    @Column(name = "source_revision_id", nullable = false)
    private UUID sourceRevisionId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected FactAssertionJpaEntity() {}

    public static FactAssertionJpaEntity from(EconomicKernel.FactAssertion assertion) {
        FactAssertionJpaEntity entity = new FactAssertionJpaEntity();
        EconomicKernel.FactKey key = assertion.key();
        entity.id = assertion.id();
        entity.organizationId = key.organizationId();
        entity.economicEntityId = key.economicEntityId();
        entity.businessScopeId = key.businessScopeId();
        entity.metricCode = key.metricCode();
        entity.metricVersion = key.metricVersion();
        entity.timeKind = key.time().kind();
        entity.startInclusive = key.time().startInclusive();
        entity.endExclusive = key.time().endExclusive();
        entity.reportingBasis = key.reportingBasis();
        entity.unitCode = key.unitCode();
        entity.currencyCode = key.currencyCode();
        entity.dimensions = new TreeMap<>(key.dimensions());
        entity.dimensionHash = dimensionHash(key.dimensions());
        entity.valueState = assertion.value().state();
        entity.numericValue = assertion.value().decimal();
        entity.estimateMethodRef = assertion.value().estimateMethodRef();
        entity.sourceRevisionId = assertion.sourceRevisionId();
        entity.recordedAt = assertion.recordedAt();
        return entity;
    }

    public UUID getId() { return id; }

    private static String dimensionHash(SortedMap<String, String> dimensions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var e : dimensions.entrySet()) {
                byte[] key = e.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] value = e.getValue().getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(key.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(key);
                digest.update(Integer.toString(value.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
