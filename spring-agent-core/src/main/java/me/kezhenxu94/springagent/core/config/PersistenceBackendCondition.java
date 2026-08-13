package me.kezhenxu94.springagent.core.config;

import me.kezhenxu94.springagent.core.config.PersistenceProperties.Type;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Backs {@link ConditionalOnPersistenceBackend}.
 *
 * <p>A plain condition evaluated at {@code PARSE_CONFIGURATION} time, deliberately not a {@code
 * ConfigurationCondition} deferring to {@code REGISTER_BEAN}: AOT processing records the outcome of
 * the configuration parse, so a condition that had not run by then would not be baked into a native
 * image at all.
 */
class PersistenceBackendCondition extends SpringBootCondition {

  @Override
  public ConditionOutcome getMatchOutcome(
      final ConditionContext context, final AnnotatedTypeMetadata metadata) {
    final var attributes =
        metadata.getAnnotationAttributes(ConditionalOnPersistenceBackend.class.getName());
    final var wanted = (Type) attributes.get("value");
    final var classLoader = context.getClassLoader();

    // Named a backend whose module was never added: say so, rather than letting it surface later as
    // an unexplained missing repository bean. The message reaches the condition evaluation report.
    final var configured = PersistenceBackendResolver.configured(context.getEnvironment());
    if (configured == wanted && !PersistenceBackendResolver.present(wanted, classLoader)) {
      return ConditionOutcome.noMatch(
          "%s is %s but %s is not on the classpath"
              .formatted(
                  PersistenceBackendResolver.TYPE_PROPERTY,
                  wanted.name().toLowerCase(),
                  PersistenceBackendResolver.moduleOf(wanted)));
    }

    final var selected = PersistenceBackendResolver.resolve(context.getEnvironment(), classLoader);
    if (selected != wanted) {
      return ConditionOutcome.noMatch(
          "the persistence backend is %s, not %s"
              .formatted(selected.name().toLowerCase(), wanted.name().toLowerCase()));
    }
    return ConditionOutcome.match(
        "the persistence backend is %s".formatted(wanted.name().toLowerCase()));
  }
}
