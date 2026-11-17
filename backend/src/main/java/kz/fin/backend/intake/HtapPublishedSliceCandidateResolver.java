package kz.fin.backend.intake;

import kz.fin.backend.security.FinanceAccessPolicy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * W1.2.A: indexed, exact read-only source candidate lookup.
 * FIN_READ is checked against the signed server-verified token. This does NOT
 * establish a trusted metric mapping or produce an ExecutionPermit.
 */
@Component
public class HtapPublishedSliceCandidateResolver {
    public enum Status {
        SOURCE_NOT_FOUND,
        SOURCE_AMBIGUOUS,
        MAPPING_CANDIDATE_NOT_FOUND,
        MAPPING_NOT_QUALIFIED,
        MAPPING_EDITORIALLY_ACTIVE_UNQUALIFIED
    }

    public record Resolution(Status status, UUID assertionId, String reasonCode) {
        public Resolution {
            Objects.requireNonNull(status);
            Objects.requireNonNull(reasonCode);
        }
        public boolean executable() { return false; }
    }

    private static final String SQL = """
        SELECT assertion_id
          FROM fin_calc.htap_published_slice_candidate_v1
         WHERE organization_id=:organization
           AND economic_entity_id=:entity
           AND business_scope_id=:scope
           AND fact_set_revision_id=:factset
           AND published_snapshot_sha256=:snapshot
           AND source_revision_id=:source
           AND form_code=:formCode
           AND form_version=:formVersion
           AND data_space=:dataSpace
           AND reporting_basis=:basis
           AND metric_code=:metric
           AND metric_version=:metricVersion
           AND time_kind=:timeKind
           AND start_inclusive=:startDate
           AND end_exclusive=:endDate
           AND currency_code IS NOT DISTINCT FROM :currency
           AND unit_code=:unit
           AND dimension_hash=:dimensionHash
         LIMIT 2
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final FinanceAccessPolicy access;

    public HtapPublishedSliceCandidateResolver(
            NamedParameterJdbcTemplate jdbc, FinanceAccessPolicy access) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.access=Objects.requireNonNull(access);
    }

    @Transactional(readOnly=true)
    public Resolution resolve(HtapFinancialSliceKey key, Jwt signedToken) {
        Objects.requireNonNull(key);
        access.require(signedToken,key.organizationId(),FinanceAccessPolicy.Permission.FIN_READ);
        var period=key.sourcePeriod();
        LocalDate start=period.type()==ReportingPeriodV5.Type.AS_OF
                ? period.asOfDate() : period.startInclusive();
        LocalDate end=period.type()==ReportingPeriodV5.Type.AS_OF
                ? period.asOfDate().plusDays(1) : period.endExclusive();

        var args=new MapSqlParameterSource()
                .addValue("organization",key.organizationId())
                .addValue("entity",key.economicEntityId())
                .addValue("scope",key.businessScopeId())
                .addValue("factset",key.publishedFactSetRevisionId())
                .addValue("snapshot",key.publishedSnapshotSha256())
                .addValue("source",key.sourceRevisionId())
                .addValue("formCode",key.formCode())
                .addValue("formVersion",key.formVersion())
                .addValue("dataSpace",key.dataSpace())
                .addValue("basis",key.reportingBasis())
                .addValue("metric",key.metricCode())
                .addValue("metricVersion",key.metricVersion())
                .addValue("timeKind",key.timeKind().name())
                .addValue("startDate",start)
                .addValue("endDate",end)
                .addValue("currency",key.currencyCode())
                .addValue("unit",key.unitCode())
                .addValue("dimensionHash",key.dimensionHash());

        List<UUID> ids=jdbc.query(SQL,args,
                (rs,rowNum)->rs.getObject(1,UUID.class));
        if(ids.isEmpty())
            return new Resolution(Status.SOURCE_NOT_FOUND,null,
                    "FIN_HTAP_EXACT_PUBLISHED_SOURCE_NOT_FOUND");
        if(ids.size()!=1)
            return new Resolution(Status.SOURCE_AMBIGUOUS,null,
                    "FIN_HTAP_EXACT_SOURCE_AMBIGUOUS");

        Long mappingCount=jdbc.queryForObject("""
            SELECT count(*)
              FROM fin_calc.htap_mapping_candidate_version
             WHERE organization_id=:organization
               AND form_code=:formCode AND form_version=:formVersion
               AND mapping_version=:mappingVersion
               AND mapping_sha256=:mappingSha
            """,new MapSqlParameterSource()
                .addValue("organization",key.organizationId())
                .addValue("formCode",key.formCode())
                .addValue("formVersion",key.formVersion())
                .addValue("mappingVersion",key.mappingVersion())
                .addValue("mappingSha",key.mappingSha256()), Long.class);
        if(mappingCount==null || mappingCount==0)
            return new Resolution(Status.MAPPING_CANDIDATE_NOT_FOUND,null,
                    "FIN_HTAP_MAPPING_VERSION_NOT_FOUND");
        // Editorial activation is a useful source/mapping provenance signal,
        // but not the audited semantic field-to-metric contract or Method S2.
        var editorial=jdbc.query("""
            SELECT a.catalog_epoch
            FROM fin_calc.htap_mapping_active a
            JOIN fin_calc.htap_mapping_candidate_version c
              ON c.organization_id=a.organization_id
             AND c.mapping_id=a.mapping_id
            WHERE a.organization_id=:organization
              AND a.form_code=:formCode AND a.form_version=:formVersion
              AND c.mapping_version=:mappingVersion
              AND a.mapping_sha256=:mappingSha
            """,new MapSqlParameterSource()
                    .addValue("organization",key.organizationId())
                    .addValue("formCode",key.formCode())
                    .addValue("formVersion",key.formVersion())
                    .addValue("mappingVersion",key.mappingVersion())
                    .addValue("mappingSha",key.mappingSha256()),
                (rs,i)->rs.getLong(1));
        if(editorial.size()==1)
            return new Resolution(Status.MAPPING_EDITORIALLY_ACTIVE_UNQUALIFIED,
                    ids.getFirst(),"FIN_HTAP_MAPPING_SEMANTIC_AND_METHOD_S2_HOLD");
        // A candidate or non-active historical mapping never acquires current
        // status merely by having a syntactically correct SHA.
        return new Resolution(Status.MAPPING_NOT_QUALIFIED,ids.getFirst(),
                "FIN_HTAP_MAPPING_NOT_CURRENTLY_APPROVED");
    }
}
