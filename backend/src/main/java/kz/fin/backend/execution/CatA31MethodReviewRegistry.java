package kz.fin.backend.execution;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * CAT-A3.1: read-only lookup of append-only S2 methodology decisions.
 * Approval of a METHOD is necessary but NOT sufficient for execution.
 * This service does not authorize a user, verify fact population or grant
 * a production execution permit.
 */
@Service
public class CatA31MethodReviewRegistry {
    public enum ReviewStatus {
        NOT_REVIEWED, REJECTED, APPROVED, REVOKED, NOT_EFFECTIVE, AMBIGUOUS
    }
    public record Key(String methodCode, String methodVersion, String formCode,
                      String formVersion, String reportingBasis) {
        public Key {
            Objects.requireNonNull(methodCode);
            Objects.requireNonNull(methodVersion);
            Objects.requireNonNull(formCode);
            Objects.requireNonNull(formVersion);
            Objects.requireNonNull(reportingBasis);
            if(methodCode.isBlank() || methodVersion.isBlank() ||
                    formCode.isBlank() || formVersion.isBlank() ||
                    !java.util.Set.of("MANAGEMENT","SEPARATE","CONSOLIDATED").contains(reportingBasis))
                throw new IllegalArgumentException("INVALID_S2_METHOD_KEY");
        }
    }
    public record Result(ReviewStatus status, UUID reviewId, String methodCode,
                         String methodVersion, String evidenceSha) {
        public Result { Objects.requireNonNull(status); }
        public boolean independentlyReviewed() { return status==ReviewStatus.APPROVED; }
        /** S2 approval alone is never a permission to execute for a client. */
        public boolean permitsExecution() { return false; }
    }
    private record Review(UUID id,String decision,String evidence,Instant starts,
                          Instant ends,Instant revoked) {}

    private final JdbcTemplate jdbc;
    public CatA31MethodReviewRegistry(JdbcTemplate jdbc) {
        this.jdbc=Objects.requireNonNull(jdbc);
    }
    /**
     * Use DB time, never the browser's clock or approval claim.
     * Any inconsistent/ambiguous state is fail-closed.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result lookup(Key key) {
        Objects.requireNonNull(key);
        List<Review> reviews=jdbc.query("""
            SELECT r.id,r.review_decision,r.review_evidence_sha,
                   r.effective_from,r.effective_until,v.revoked_at
              FROM fin_calc.method_s2_review r
              LEFT JOIN fin_calc.method_s2_revocation v ON v.review_id=r.id
             WHERE r.method_code=? AND r.method_version=?
               AND r.form_code=? AND r.form_version=?
               AND r.reporting_basis=?
             ORDER BY r.reviewed_at,r.id
            """,(rs,i)->new Review(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getString(3),rs.getTimestamp(4).toInstant(),
                rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6)==null?null:rs.getTimestamp(6).toInstant()),
                key.methodCode(),key.methodVersion(),key.formCode(),
                key.formVersion(),key.reportingBasis());
        if(reviews.isEmpty())return new Result(ReviewStatus.NOT_REVIEWED,null,
                key.methodCode(),key.methodVersion(),null);
        // Conflicting independent decisions for the same method variant need
        // manual adjudication, never implicit approval of the earlier one.
        if(reviews.stream().anyMatch(x->!"APPROVED".equals(x.decision())
                && !"REJECTED".equals(x.decision())))
            return new Result(ReviewStatus.AMBIGUOUS,null,
                    key.methodCode(),key.methodVersion(),null);
        if(reviews.stream().anyMatch(x->"APPROVED".equals(x.decision()))
                && reviews.stream().anyMatch(x->"REJECTED".equals(x.decision())))
            return new Result(ReviewStatus.AMBIGUOUS,null,
                    key.methodCode(),key.methodVersion(),null);
        List<Review> approved=reviews.stream().filter(x->"APPROVED".equals(x.decision())).toList();
        if(approved.isEmpty())return new Result(ReviewStatus.REJECTED,null,
                key.methodCode(),key.methodVersion(),null);
        if(approved.size()!=1)return new Result(ReviewStatus.AMBIGUOUS,null,
                key.methodCode(),key.methodVersion(),null);
        Review r=approved.getFirst();
        if(r.revoked()!=null)return new Result(ReviewStatus.REVOKED,r.id(),
                key.methodCode(),key.methodVersion(),r.evidence());
        // Signed reference is relevant to the current server time and must
        // not be confused with the expiry of the underlying source facts.
        Instant now=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",
                (rs,i)->rs.getTimestamp(1).toInstant());
        if(now==null || now.isBefore(r.starts()) || !now.isBefore(r.ends()))
            return new Result(ReviewStatus.NOT_EFFECTIVE,r.id(),
                    key.methodCode(),key.methodVersion(),r.evidence());
        return new Result(ReviewStatus.APPROVED,r.id(),
                key.methodCode(),key.methodVersion(),r.evidence());
    }
}
