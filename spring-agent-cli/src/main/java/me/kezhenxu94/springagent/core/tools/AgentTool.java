package me.kezhenxu94.springagent.core.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import me.kezhenxu94.springagent.core.agent.AgentScenario;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {
  AgentScenario[] scenario() default {AgentScenario.ALL};
}
