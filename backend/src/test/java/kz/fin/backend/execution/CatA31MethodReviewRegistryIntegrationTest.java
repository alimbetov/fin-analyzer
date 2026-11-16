package kz.fin.backend.execution;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class CatA31MethodReviewRegistryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fin")
            .withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CatA31MethodReviewRegistry review;
    private static final String SHA="a".repeat(64);

    private CatA31MethodReviewRegistry.Key key(String version) {
        return new CatA31MethodReviewRegistry.Key(
            "FIN-04/CURRENT_RATIO",version,"F01","FIN-LIQUIDITY-V1","MANAGEMENT");
    }
    private UUID insert(String version,String decision,String start,String end){
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.method_s2_review
                (id,method_code,method_version,form_code,form_version,
                 reporting_basis,semantic_contract_sha,golden_cases_sha,
                 review_evidence_sha,submitted_by,reviewed_by,review_decision,
                 review_reason,effective_from,effective_until)
            VALUES (?,'FIN-04/CURRENT_RATIO',?,'F01','FIN-LIQUIDITY-V1',
                 'MANAGEMENT',?,?,?,'author','independent-reviewer',?,
                 'Independent methodology review fixture',
                 CURRENT_TIMESTAMP + CAST(? AS interval),
                 CURRENT_TIMESTAMP + CAST(? AS interval))
            """,id,version,SHA,SHA,SHA,decision,start,end);
        return id;
    }
    @Test void notReviewedAndRejectedAreNotApproved(){
        assertEquals(CatA31MethodReviewRegistry.ReviewStatus.NOT_REVIEWED,
            review.lookup(key("T-"+UUID.randomUUID())).status());
        String version="T-"+UUID.randomUUID();
        insert(version,"REJECTED","-1 hour","1 day");
        var result=review.lookup(key(version));
        assertEquals(CatA31MethodReviewRegistry.ReviewStatus.REJECTED,result.status());
        assertFalse(result.permitsExecution());
    }
    @Test void approvalIsVersionPinnedImmutableAndRevocable(){
        String version="T-"+UUID.randomUUID();
        UUID id=insert(version,"APPROVED","-1 hour","1 day");
        var approved=review.lookup(key(version));
        assertEquals(CatA31MethodReviewRegistry.ReviewStatus.APPROVED,approved.status());
        assertEquals(id,approved.reviewId());
        assertFalse(approved.permitsExecution());
        assertThrows(DataAccessException.class,()->jdbc.update(
            "UPDATE fin_calc.method_s2_review SET review_reason='mutated' WHERE id=?",id));
        assertThrows(DataAccessException.class,()->jdbc.update(
            "DELETE FROM fin_calc.method_s2_review WHERE id=?",id));
        UUID event=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.method_s2_revocation
                (id,review_id,revoked_by,reason,evidence_sha)
            VALUES (?,?,'review-supervisor','Material methodology issue',?)
            """,event,id,SHA);
        assertEquals(CatA31MethodReviewRegistry.ReviewStatus.REVOKED,
            review.lookup(key(version)).status());
        assertThrows(DataAccessException.class,()->jdbc.update(
            "DELETE FROM fin_calc.method_s2_revocation WHERE id=?",event));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            INSERT INTO fin_calc.method_s2_revocation
                (id,review_id,revoked_by,reason,evidence_sha)
            VALUES (?,?,'supervisor','duplicate',?)
            """,UUID.randomUUID(),id,SHA));
    }
    @Test void contradictoryReviewDecisionsForSameVersionNeverQualify(){
        String version="T-"+UUID.randomUUID();
        insert(version,"APPROVED","-1 hour","1 day");
        insert(version,"REJECTED","-1 hour","1 day");
        var decision=review.lookup(key(version));
        assertEquals(CatA31MethodReviewRegistry.ReviewStatus.AMBIGUOUS,
            decision.status());
        assertFalse(decision.independentlyReviewed());
        assertFalse(decision.permitsExecution());
    }
    @Test void timeBoundAndIdentityConstraintsFailClosed(){
        String future="T-"+UUID.randomUUID();
        insert(future,"APPROVED","1 day","2 days");
        assertEquals(CatA31MethodReviewRegistry.ReviewStatus.NOT_EFFECTIVE,
            review.lookup(key(future)).status());
        String expired="T-"+UUID.randomUUID();
        insert(expired,"APPROVED","-2 days","-1 day");
        assertEquals(CatA31MethodReviewRegistry.ReviewStatus.NOT_EFFECTIVE,
            review.lookup(key(expired)).status());

        String self="T-"+UUID.randomUUID();
        assertThrows(DataAccessException.class,()->jdbc.update("""
            INSERT INTO fin_calc.method_s2_review
                (id,method_code,method_version,form_code,form_version,
                 reporting_basis,semantic_contract_sha,golden_cases_sha,
                 review_evidence_sha,submitted_by,reviewed_by,review_decision,
                 review_reason,effective_from,effective_until)
            VALUES (?,'FIN-04/CURRENT_RATIO',?,'F01','FIN-LIQUIDITY-V1',
                 'MANAGEMENT',?,?,?,'same-person','same-person','APPROVED',
                 'invalid self approval',now()-interval '1 hour',now()+interval '1 day')
            """,UUID.randomUUID(),self,SHA,SHA,SHA));
    }
}
