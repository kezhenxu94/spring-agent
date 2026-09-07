package me.kezhenxu94.springagent.core.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Matches when a property has a value that is actually a value.
 *
 * <p>{@code @ConditionalOnProperty} cannot express this, and the difference is not academic. Every
 * {@code application.yaml} in this repository names its settings as {@code ${SOME_VAR:}}, so a
 * setting nobody configured is <em>present and empty</em> rather than absent — and
 * {@code @ConditionalOnProperty} with no {@code havingValue} matches anything that is not the
 * literal {@code false}, empty string included. A feature gated that way switches itself on with
 * nothing to work from: a model name of {@code ""} reaches the endpoint and comes back as {@code
 * The length of model should be between 1 and 512}, which reads to the agent as a broken endpoint
 * rather than as a feature that was never configured.
 *
 * <p>{@link ConditionalOnUserModels} is the same lesson learned about one specific key, and is
 * where this generalises from. Prefer a real {@code app.<thing>.enabled} flag where the thing has
 * one; this is for the case where the configuration <em>is</em> the switch, as a model name is —
 * there is nothing to name a model for if there is no model.
 *
 * <p>Evaluated at configuration-parse time, so it is baked into a native image; the same caveat as
 * {@link ConditionalOnShellBackend}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@Conditional(NonBlankPropertyCondition.class)
public @interface ConditionalOnNonBlankProperty {

  /** The property that has to hold something. */
  String value();
}
