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
