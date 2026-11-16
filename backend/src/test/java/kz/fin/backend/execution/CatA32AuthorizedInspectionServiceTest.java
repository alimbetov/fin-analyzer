package kz.fin.backend.execution;

import kz.fin.backend.security.FinanceAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CatA32AuthorizedInspectionServiceTest {
    private static Jwt jwt(UUID organization,String permission){
        return Jwt.withTokenValue("unit-test").header("alg","RS256").subject("actor")
            .claim("fin_access",List.of(Map.of(
                "organizationId",organization.toString(),
                "permissions",List.of(permission)))).build();
    }
    @Test void onlyCorrectTenantAnalyzePermissionMayReachInspector(){
        var scope=new MethodTaskAdapter.TrustedRevision(UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),UUID.randomUUID(),"fixed-sha","ACTUAL");
        var method=new CatA31MethodReviewRegistry.Key("FIN-04/CURRENT_RATIO","1",
            "F01","FIN-LIQUIDITY-V1","MANAGEMENT");
        var inspector=mock(CatA31QualificationInspector.class);
        var facade=new CatA32AuthorizedInspectionService(new FinanceAccessPolicy(),inspector);
        assertThrows(AccessDeniedException.class,()->facade.inspect(
            jwt(scope.organizationId(),"FIN_READ"),method,scope,List.of()));
        assertThrows(AccessDeniedException.class,()->facade.inspect(
            jwt(UUID.randomUUID(),"FIN_ANALYZE"),method,scope,List.of()));
        assertThrows(AccessDeniedException.class,()->facade.inspect(
            null,method,scope,List.of()));
        verifyNoInteractions(inspector);
        var hold=new CatA31QualificationInspector.Decision(
            CatA31QualificationInspector.Status.METHOD_S2_HOLD,"NOT_REVIEWED");
        when(inspector.inspect(eq(method),eq(scope),anyList())).thenReturn(hold);
        assertEquals(hold,facade.inspect(jwt(scope.organizationId(),"FIN_ANALYZE"),
            method,scope,List.of()));
        verify(inspector,times(1)).inspect(eq(method),eq(scope),anyList());
        assertFalse(hold.permitsExecution());
    }
}
