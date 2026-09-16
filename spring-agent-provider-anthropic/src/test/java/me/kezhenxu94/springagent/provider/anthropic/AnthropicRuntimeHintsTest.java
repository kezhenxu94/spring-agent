package me.kezhenxu94.springagent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import me.kezhenxu94.springagent.provider.anthropic.aot.AnthropicRuntimeHints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;

/**
 * That the hints registrar registers something, and not everything.
 *
 * <p>Both halves matter and both fail silently otherwise. This class enumerates the SDK's jar
 * rather than reading a {@code reflect-config.json} — because anthropic-java-core ships none — and
 * every failure inside it is an empty list rather than an exception, which is right for a build
 * without the SDK and catastrophic if it ever becomes the normal case: the JVM build passes either
 * way, and only a native binary fails, at run time, on a class nobody registered.
 *
 * <p>The other half is the ceiling. The jar holds over eight thousand classes, most of them the
 * beta and batch APIs this project never calls, so an allow-list that stopped narrowing would put
 * Anthropic's entire API surface into every binary — the weight {@code OpenAiSdkRuntimeHints}'
 * comment warns about.
 */
class AnthropicRuntimeHintsTest {

  private Set<String> registered() {
    final var hints = new RuntimeHints();
    new AnthropicRuntimeHints().registerHints(hints, getClass().getClassLoader());
    return hints
        .reflection()
        .typeHints()
        .map(hint -> hint.getType().getName())
        .collect(Collectors.toSet());
  }

  @Test
  @DisplayName("the SDK's own message types are registered, so the walk actually found the jar")
  void theWalkFoundTheJar() {
    final var types = registered();

    assertThat(types).contains("com.anthropic.models.messages.MessageCreateParams");
    // The builders and companions everything is constructed through are separate class entries, so
    // a package walk picks them up — which is the reason it is a walk and not a list.
    assertThat(types)
        .anyMatch(
            name -> name.startsWith("com.anthropic.models.messages.") && name.contains("$Builder"));
    // The exception a rejection is read off.
    assertThat(types).contains("com.anthropic.errors.BadRequestException");
  }

  @Test
  @DisplayName("this module's own bound records are registered, which nobody else states")
  void theBoundRecordsAreRegistered() {
    assertThat(registered())
        .contains(AnthropicProperties.class.getName(), AnthropicProperties.Vertex.class.getName());
  }

  @Test
  @DisplayName("the allow-list narrows: the APIs this project never calls stay out")
  void theAllowListNarrows() {
    final var types = registered();

    assertThat(types).noneMatch(name -> name.startsWith("com.anthropic.models.beta."));
    assertThat(types).noneMatch(name -> name.startsWith("com.anthropic.models.batches."));
    // A sanity ceiling rather than an exact count, which would break on every SDK bump. The whole
    // jar is over eight thousand classes; this should be a small fraction of it.
    assertThat(types).hasSizeLessThan(3000);
  }
}
