package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatA3MethodFactAdmissionTest {
    private static MethodTaskAdapter.TrustedRevision revision(){
        return new MethodTaskAdapter.TrustedRevision(
            UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
            "sha256:fixture","ACTUAL");
    }
    private static CatA3MethodFactAdmission guard(JdbcTemplate jdbc){
        return new CatA3MethodFactAdmission(jdbc,new ObjectMapper());
    }
    @Test void unknownMethodAndSectorFormNeverIssueProductionPermit(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var gate=guard(jdbc);
        assertEquals(CatA3MethodFactAdmission.Status.METHOD_UNKNOWN,
            gate.inspect("UNKNOWN-99","F01",revision()).status());
        assertEquals(CatA3MethodFactAdmission.Status.NOT_IN_PILOT,
            gate.inspect("FIN-04","SF-C01",revision()).status());
        verifyNoInteractions(jdbc);
    }
    @Test void nullScopeAndUnknownSpaceRejectedBeforeDatabaseLookup(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var gate=guard(jdbc);
        assertEquals(CatA3MethodFactAdmission.Status.INPUT_INVALID,
            gate.inspect("FIN-04","F01",null).status());
        var r=revision();
        var invalid=new MethodTaskAdapter.TrustedRevision(
            r.organizationId(),r.economicEntityId(),r.businessScopeId(),
            r.factSetRevisionId(),r.snapshotHash(),"CLIENT_APPROVED");
        assertEquals(CatA3MethodFactAdmission.Status.INPUT_INVALID,
            gate.inspect("FIN-04","F01",invalid).status());
        verifyNoInteractions(jdbc);
    }
    @Test void publishedFactMustMatchTrustedSnapshotAndDataSpace(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var gate=guard(jdbc);
        var rev=revision();
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenReturn(List.of());
        var absent=gate.inspect("FIN-04/CURRENT_RATIO","F01",rev);
        assertEquals(CatA3MethodFactAdmission.Status.SOURCE_NOT_PUBLISHED_OR_CHANGED,absent.status());
        assertFalse(absent.allowsActualExecution());
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenReturn(List.of(new CatA3MethodFactAdmission.Publication("bad-hash","ACTUAL","MANAGEMENT")));
        assertEquals(CatA3MethodFactAdmission.Status.SOURCE_NOT_PUBLISHED_OR_CHANGED,
            gate.inspect("FIN-04","F01",rev).status());
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenReturn(List.of(new CatA3MethodFactAdmission.Publication(rev.snapshotHash(),"SYNTHETIC","MANAGEMENT")));
        assertEquals(CatA3MethodFactAdmission.Status.SOURCE_SCOPE_MISMATCH,
            gate.inspect("FIN-04","F01",rev).status());
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenReturn(List.of(new CatA3MethodFactAdmission.Publication(rev.snapshotHash(),"ACTUAL","MANAGEMENT")));
        var matched=gate.inspect("FIN-04","F01",rev);
        assertEquals(CatA3MethodFactAdmission.Status.METHOD_S2_HOLD,matched.status());
        assertFalse(matched.allowsActualExecution());
    }
    @Test void forgedPublishedStatusCannotOverrideMethodReviewHold(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var rev=revision();
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenReturn(List.of(new CatA3MethodFactAdmission.Publication(
                rev.snapshotHash(),"ACTUAL","CONSOLIDATED")));
        var d=guard(jdbc).inspect("FIN-04/CURRENT_RATIO","F01",rev);
        assertEquals(CatA3MethodFactAdmission.Status.METHOD_S2_HOLD,d.status());
        assertFalse(d.allowsActualExecution());
    }
}
