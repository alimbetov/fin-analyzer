package kz.fin.backend.execution;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test-only authority for historical finance golden fixtures. This file is
 * exclusively under src/test; it is not an ExecutionPermit, S2 approval or a
 * production feature flag. Never import into authorization/security tests.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LegacyExecutorGoldenFixturePermit {
    @Bean
    @Primary
    LegacyExecutionPermitGuard allowLegacyGoldenFixtureOnly() {
        return () -> { };
    }
}
