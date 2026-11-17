package kz.fin.backend.intake;

import kz.fin.backend.security.FinanceAccessPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Internal W1.2.A proposal / independent review / monotonic catalog epoch.
 * The supplied Jwt must originate at the trusted verified Spring Security
 * boundary. This component exposes no HTTP endpoint or execution permission.
 *
 * APPROVED is an editorial mapping decision only, NOT qualification of a
 * financial metric, executable method, algorithm artifact or FIN ACTUAL.
 */
@Component
public class HtapMappingGovernanceService {
    private final JdbcTemplate jdbc;
    private final FinanceAccessPolicy access;

    public HtapMappingGovernanceService(JdbcTemplate jdbc, FinanceAccessPolicy access) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.access = Objects.requireNonNull(access);
    }

    @Transactional
    public UUID propose(UUID organization, String formCode, String formVersion,
                        String mappingVersion, String jsonObject, Jwt trustedJwt) {
        access.require(trustedJwt,organization,FinanceAccessPolicy.Permission.FIN_SUBMIT);
        String subject=access.subject(trustedJwt);
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.htap_mapping_candidate_version(
                mapping_id, organization_id, form_code, form_version, mapping_version,
                mapping_payload, mapping_sha256, authored_by)
            VALUES(?,?,?,?,?,CAST(? AS jsonb),?,?)
            """,id,organization,formCode,formVersion,mappingVersion,jsonObject,
                "0".repeat(64),subject);
        jdbc.update("""
            INSERT INTO fin_calc.htap_mapping_authorship(
                organization_id,mapping_id,author_subject)
            VALUES (?,?,?)
            """,organization,id,subject);
        return id;
    }

    @Transactional
    public UUID review(UUID organization, UUID mappingId, boolean approved,
                       String evidenceSha256, Jwt trustedJwt) {
        access.require(trustedJwt,organization,FinanceAccessPolicy.Permission.FIN_REVIEW);
        UUID reviewId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.htap_mapping_review(
                review_id,mapping_id,organization_id,decision,
                reviewer_subject,evidence_sha256)
            VALUES(?,?,?,?,?,?)
            """,reviewId,mappingId,organization,
                approved?"APPROVED":"REJECTED",access.subject(trustedJwt),evidenceSha256);
        return reviewId;
    }

    /**
     * Verifies a reviewed mapping against the immutable pilot field dictionary,
     * a QUALIFIED metric contract, and the pinned ISO MONTH FLOW period policy.
     * All semantic fields come from existing DB evidence, not caller JSON.
     * This is not an IFRS tag, independent method S2, or runtime permit.
     */
    @Transactional
    public UUID validatePilotSemantics(UUID organization, UUID mappingId, Jwt trustedJwt) {
        access.require(trustedJwt,organization,FinanceAccessPolicy.Permission.FIN_REVIEW);
        String reviewer=access.subject(trustedJwt);
        int inserted=jdbc.update("""
            INSERT INTO fin_calc.htap_mapping_semantic_evidence(
              organization_id,mapping_id,source_field,metric_code,metric_version,
              measure_kind,time_kind,unit_code,period_policy_code,semantic_standard,
              semantic_version,reviewer_subject,evidence_sha256)
            SELECT c.organization_id,c.mapping_id,f.source_field,
                   m.metric_code,m.metric_version,m.measure_kind,
                   f.required_time_kind,m.unit_code,
                   'ISO_MONTH_FLOW_PERIOD_ONLY_V1','INTERNAL_FIN_PILOT',
                   'FIN_PILOT_V1',r.reviewer_subject,r.evidence_sha256
              FROM fin_calc.htap_mapping_candidate_version c
              JOIN fin_calc.htap_mapping_review r
                ON r.organization_id=c.organization_id
               AND r.mapping_id=c.mapping_id AND r.decision='APPROVED'
              JOIN fin_fact.pilot_field_mapping f
                ON f.form_code=c.form_code AND f.form_version=c.form_version
               AND f.source_field=c.mapping_payload->>'sourceField'
               AND f.metric_code=c.mapping_payload->>'metricCode'
               AND f.metric_version=c.mapping_payload->>'metricVersion'
              JOIN fin_calc.metric_contract m
                ON m.metric_code=f.metric_code AND m.metric_version=f.metric_version
               AND m.qualification='QUALIFIED'
               AND m.measure_kind='FLOW'
               AND f.required_time_kind='INTERVAL'
             WHERE c.organization_id=? AND c.mapping_id=?
               AND r.reviewer_subject=?
            """,organization,mappingId,reviewer);
        if(inserted!=1)
            throw new IllegalArgumentException("FIN_HTAP_MAPPING_SEMANTICS_UNQUALIFIED");
        return mappingId;
    }

    @Transactional
    public long activate(UUID organization, String formCode, String formVersion,
                         UUID mappingId, long expectedCatalogEpoch, Jwt trustedJwt) {
        access.require(trustedJwt,organization,FinanceAccessPolicy.Permission.FIN_PUBLISH);
        Long result=jdbc.queryForObject("""
            SELECT fin_calc.htap_mapping_activate_v1(?,?,?,?,?,?)
            """,Long.class,organization,formCode,formVersion,mappingId,
                expectedCatalogEpoch,access.subject(trustedJwt));
        return Objects.requireNonNull(result);
    }
}
