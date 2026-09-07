package me.kezhenxu94.springagent.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Backs {@link ConditionalOnNonBlankProperty}; see there for why blank is not configured. */
class NonBlankPropertyCondition extends SpringBootCondition {

  @Override
  public ConditionOutcome getMatchOutcome(
      final ConditionContext context, final AnnotatedTypeMetadata metadata) {
    final var attributes =
        metadata.getAnnotationAttributes(ConditionalOnNonBlankProperty.class.getName());
    if (attributes == null) {
      return ConditionOutcome.noMatch("no @ConditionalOnNonBlankProperty to read");
    }
    final var property = (String) attributes.get("value");
    final var value = context.getEnvironment().getProperty(property);
    if (value == null || value.isBlank()) {
      // "not set" rather than "absent": the property is very often present and empty, which is the
      // whole reason this condition exists, and a report saying "absent" would send the reader
      // looking for a missing line rather than an unset variable.
      return ConditionOutcome.noMatch("%s is not set".formatted(property));
    }
    return ConditionOutcome.match("%s is set".formatted(property));
  }
}
