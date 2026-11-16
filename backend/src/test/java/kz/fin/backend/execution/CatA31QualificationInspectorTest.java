package kz.fin.backend.execution;

import kz.fin.backend.persistence.TrustedFinancialPopulationService;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatA31QualificationInspectorTest {
    private final CatA3MethodFactAdmission publication=mock(CatA3MethodFactAdmission.class);
    private final CatA31MethodReviewRegistry reviews=mock(CatA31MethodReviewRegistry.class);
    private final TrustedFinancialPopulationService population=mock(TrustedFinancialPopulationService.class);
    private final CatA31QualificationInspector inspector =
        new CatA31QualificationInspector(publication,reviews,population);
    private final CatA31MethodReviewRegistry.Key key =
        new CatA31MethodReviewRegistry.Key("FIN-04/CURRENT_RATIO","1.0.0",
            "F01","FIN-LIQUIDITY-V1","MANAGEMENT");
    private final MethodTaskAdapter.TrustedRevision revision =
        new MethodTaskAdapter.TrustedRevision(UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),UUID.randomUUID(),"fixture-hash","ACTUAL");
    private final List<TrustedFinancialPopulationService.RequiredGroup> groups=List.of(
        new TrustedFinancialPopulationService.RequiredGroup(UUID.randomUUID(),
            "CURRENT_ASSETS",LocalDate.of(2026,10,31),LocalDate.of(2026,11,1)));

    private void publicationOK() {
        when(publication.inspect(key.methodCode(),key.formCode(),revision))
            .thenReturn(new CatA3MethodFactAdmission.Decision(
                CatA3MethodFactAdmission.Status.METHOD_S2_HOLD,
                key.methodCode(),key.formCode(),revision.factSetRevisionId().toString()));
    }
    @Test void missingGroupsAndNonActualNeverQueryPublicationOrReview() {
        assertEquals(CatA31QualificationInspector.Status.INVALID_REQUEST,
            inspector.inspect(key,revision,List.of()).status());
        var synthetic=new MethodTaskAdapter.TrustedRevision(revision.organizationId(),
            revision.economicEntityId(),revision.businessScopeId(),
            revision.factSetRevisionId(),revision.snapshotHash(),"SYNTHETIC");
        assertEquals(CatA31QualificationInspector.Status.INVALID_REQUEST,
            inspector.inspect(key,synthetic,groups).status());
        verifyNoInteractions(publication,reviews,population);
    }
    @Test void rejectedPublicationBlocksS2AndPopulation() {
        when(publication.inspect(key.methodCode(),key.formCode(),revision))
            .thenReturn(new CatA3MethodFactAdmission.Decision(
                CatA3MethodFactAdmission.Status.SOURCE_NOT_PUBLISHED_OR_CHANGED,
                key.methodCode(),key.formCode(),revision.factSetRevisionId().toString()));
        assertEquals(CatA31QualificationInspector.Status.PUBLICATION_REJECTED,
            inspector.inspect(key,revision,groups).status());
        verifyNoInteractions(reviews,population);
    }
    @Test void absentIndependentReviewBlocksPopulation() {
        publicationOK();
        when(reviews.lookup(key)).thenReturn(new CatA31MethodReviewRegistry.Result(
            CatA31MethodReviewRegistry.ReviewStatus.REVOKED,
            UUID.randomUUID(),key.methodCode(),key.methodVersion(),"a".repeat(64)));
        var decision=inspector.inspect(key,revision,groups);
        assertEquals(CatA31QualificationInspector.Status.METHOD_S2_HOLD,decision.status());
        assertFalse(decision.permitsExecution());
        verifyNoInteractions(population);
    }
    @Test void approvedS2ButInvalidPopulationCannotRun() {
        publicationOK();
        when(reviews.lookup(key)).thenReturn(new CatA31MethodReviewRegistry.Result(
            CatA31MethodReviewRegistry.ReviewStatus.APPROVED,
            UUID.randomUUID(),key.methodCode(),key.methodVersion(),"a".repeat(64)));
        when(population.requireReviewedActual(revision.organizationId(),
            revision.factSetRevisionId(),groups))
            .thenThrow(new IllegalArgumentException("SOURCE_POPULATION_MISSING_REGISTERED_ROWS"));
        var result=inspector.inspect(key,revision,groups);
        assertEquals(CatA31QualificationInspector.Status.SOURCE_REVIEW_OR_POPULATION_REJECTED,
            result.status());
        assertEquals("CAT_A31_SOURCE_REVIEW_REQUIRED",result.reasonCode());
        assertFalse(result.permitsExecution());
    }
    @Test void evenReviewedPopulationRequiresSeparateAuthorizedExecutorRelease() {
        publicationOK();
        when(reviews.lookup(key)).thenReturn(new CatA31MethodReviewRegistry.Result(
            CatA31MethodReviewRegistry.ReviewStatus.APPROVED,
            UUID.randomUUID(),key.methodCode(),key.methodVersion(),"a".repeat(64)));
        var fact=new TrustedFinancialPopulationService.MappedFact(UUID.randomUUID(),
            groups.getFirst().sourceRevisionId(),"F01","FIN-LIQUIDITY-V1",
            "balance","currentAssets","CURRENT_ASSETS",LocalDate.of(2026,10,31),
            LocalDate.of(2026,11,1),new java.math.BigDecimal("100"),"KZT");
        when(population.requireReviewedActual(revision.organizationId(),
            revision.factSetRevisionId(),groups))
            .thenReturn(new TrustedFinancialPopulationService.VerifiedPopulation(
                revision.factSetRevisionId(),List.of(fact)));
        var result=inspector.inspect(key,revision,groups);
        assertEquals(CatA31QualificationInspector.Status.REVIEWED_INPUTS_RELEASE_HOLD,
            result.status());
        assertFalse(result.permitsExecution());
    }
}
