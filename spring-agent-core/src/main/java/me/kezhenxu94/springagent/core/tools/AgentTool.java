package me.kezhenxu94.springagent.core.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an object whose {@code @Tool} methods are offered to the agent. Allowed on a {@code @Bean}
 * factory method too, for a tool whose type comes from a library and cannot be annotated itself.
 *
 * <p>Every annotated bean is offered to every run. A tool that belongs only in some of them
 * implements {@link ScenarioGatedTool} and says so in code, where it can speak of a scenario this
 * runtime does not ship.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {}
