package me.kezhenxu94.springagent.core.config;

import java.util.List;
import java.util.stream.Collectors;
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
    final var wanted = List.of((Type[]) attributes.get("value"));
    final var classLoader = context.getClassLoader();

    // Named a backend whose module was never added: say so, rather than letting it surface later as
    // an unexplained missing repository bean. The message reaches the condition evaluation report.
    final var configured = PersistenceBackendResolver.configured(context.getEnvironment());
    if (wanted.contains(configured)
        && !PersistenceBackendResolver.present(configured, classLoader)) {
      return ConditionOutcome.noMatch(
          "%s is %s but %s is not on the classpath"
              .formatted(
                  PersistenceBackendResolver.TYPE_PROPERTY,
                  configured.name().toLowerCase(),
                  PersistenceBackendResolver.moduleOf(configured)));
    }

    final var selected = PersistenceBackendResolver.resolve(context.getEnvironment(), classLoader);
    if (!wanted.contains(selected)) {
      return ConditionOutcome.noMatch(
          "the persistence backend is %s, not %s"
              .formatted(selected.name().toLowerCase(), names(wanted)));
    }
    return ConditionOutcome.match(
        "the persistence backend is %s".formatted(selected.name().toLowerCase()));
  }

  private static String names(final List<Type> types) {
    return types.stream()
        .map(type -> type.name().toLowerCase())
        .collect(Collectors.joining(" or "));
  }
}
