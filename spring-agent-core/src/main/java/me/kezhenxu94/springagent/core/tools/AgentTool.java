package me.kezhenxu94.springagent.core.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import me.kezhenxu94.springagent.core.agent.AgentScenario;

/**
 * Marks an object whose {@code @Tool} methods are offered to the agent. Allowed on a {@code @Bean}
 * factory method too, for a tool whose type comes from a library and cannot be annotated itself.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {
  AgentScenario[] scenario() default {AgentScenario.ALL};
}
