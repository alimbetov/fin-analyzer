package kz.fin.backend.execution;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduler infrastructure; actual FIN consumption remains disabled
 * unless fin.execution.enabled=true.
 */
@Configuration
@EnableScheduling
public class ExecutionSchedulingConfiguration {}
