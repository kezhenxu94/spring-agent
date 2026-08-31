package me.kezhenxu94.springagent.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Backs {@link ConditionalOnUserModels}.
 *
 * <p>Evaluated at configuration-parse time for the reason {@link ShellBackendCondition} spells out:
 * AOT records the parse, so a deferred condition would not be baked into a native image.
 */
class UserModelsCondition extends SpringBootCondition {

  static final String KEY_PROPERTY = "app.ai.user-models.encryption-key";

  @Override
  public ConditionOutcome getMatchOutcome(
      final ConditionContext context, final AnnotatedTypeMetadata metadata) {
    final var key = context.getEnvironment().getProperty(KEY_PROPERTY);
    if (key == null || key.isBlank()) {
      return ConditionOutcome.noMatch(
          "%s is not set, so a user API token could not be stored sealed".formatted(KEY_PROPERTY));
    }
    return ConditionOutcome.match("%s is set".formatted(KEY_PROPERTY));
  }
}
