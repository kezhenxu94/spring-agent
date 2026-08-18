package me.kezhenxu94.springagent.core.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an object whose {@code @Tool} methods are offered to the agent. Allowed on a {@code @Bean}
 * factory method too, for a tool whose type comes from a library and cannot be annotated itself.
 *
 * <p>Every annotated bean is offered to every run, unless the run's {@code AgentScenario} keeps it
 * out — the scenario decides, so a consumer's own scenario can rule on tools this runtime ships and
 * the other way round.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {}
