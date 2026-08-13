package me.kezhenxu94.springagent.core.config;

import me.kezhenxu94.springagent.core.config.ShellToolsProperties.Type;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Backs {@link ConditionalOnShellBackend}.
 *
 * <p>A plain condition evaluated at {@code PARSE_CONFIGURATION} time, deliberately not a {@code
 * ConfigurationCondition} deferring to {@code REGISTER_BEAN}: AOT processing records the outcome of
 * the configuration parse, so a condition that had not run by then would not be baked into a native
 * image at all.
 */
class ShellBackendCondition extends SpringBootCondition {

  @Override
  public ConditionOutcome getMatchOutcome(
      final ConditionContext context, final AnnotatedTypeMetadata metadata) {
    final var attributes =
        metadata.getAnnotationAttributes(ConditionalOnShellBackend.class.getName());
    final var wanted = (Type) attributes.get("value");

    final var selected = ShellBackendResolver.resolve(context.getEnvironment());
    if (selected != wanted) {
      return ConditionOutcome.noMatch(
          "the shell is %s, not %s"
              .formatted(selected.name().toLowerCase(), wanted.name().toLowerCase()));
    }

    // Named a shell whose module was never added: say so, rather than leaving an agent that quietly
    // has no Bash tool. The message reaches the condition evaluation report.
    if (!ShellBackendResolver.present(wanted, context.getClassLoader())) {
      return ConditionOutcome.noMatch(
          "%s is %s but %s is not on the classpath"
              .formatted(
                  ShellBackendResolver.TYPE_PROPERTY,
                  wanted.name().toLowerCase(),
                  ShellBackendResolver.artifactFor(wanted)));
    }

    return ConditionOutcome.match("the shell is %s".formatted(wanted.name().toLowerCase()));
  }
}
