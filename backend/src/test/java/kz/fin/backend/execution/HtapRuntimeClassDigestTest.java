package kz.fin.backend.execution;

import kz.fin.backend.analytics.ConcentrationAnalysisEngine;
import kz.fin.backend.methodology.Fin01RevenueMomSqlModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** W1.2.A/R2.B: real loaded code hash is evidence, never an ExecutionPermit. */
class HtapRuntimeClassDigestTest {
    @Test void computesDeterministicClassHashWithoutAttestationOrExecution() {
        var sql=HtapRuntimeClassDigest.inspect(Fin01RevenueMomSqlModule.class);
        var sqlAgain=HtapRuntimeClassDigest.inspect(Fin01RevenueMomSqlModule.class);
        var pareto=HtapRuntimeClassDigest.inspect(ConcentrationAnalysisEngine.class);
        assertEquals(sql,sqlAgain);
        assertEquals(64,sql.classSha256().length());
        assertNotEquals(sql.classSha256(),pareto.classSha256());
        assertEquals("CLASS_BYTES_ONLY_UNATTESTED",sql.trustLevel());
        assertFalse(sql.executionPermitted());
        assertFalse(pareto.executionPermitted());
        assertThrows(IllegalArgumentException.class,
                ()->HtapRuntimeClassDigest.inspect(Runnable.class));
    }
}
