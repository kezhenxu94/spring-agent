package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * That {@code app.ai.system-prompt} can be written as one string, or as several pieces kept apart
 * rather than joined — see {@code SpringAgent#systemMessagesFor}, which is what turns the pieces
 * this class resolves into one {@code SystemMessage} each.
 */
class SystemPromptPartsTest {

  private static StandardEnvironment environmentWith(final Map<String, Object> source) {
    final var environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("test", source));
    return environment;
  }

  private static List<String> resolvedParts(final Map<String, Object> source) {
    final var environment = environmentWith(source);
    new SystemPromptParts().postProcessEnvironment(environment, new SpringApplication());
    final var parts = new ArrayList<String>();
    for (int i = 0; environment.containsProperty("app.ai.system-prompt-parts[" + i + "]"); i++) {
      parts.add(environment.getProperty("app.ai.system-prompt-parts[" + i + "]"));
    }
    return parts;
  }

  @Test
  @DisplayName("nothing configured leaves no parts, for the single-message path to be used")
  void nothingConfigured() {
    assertThat(resolvedParts(Map.of())).isEmpty();
  }

  @Test
  @DisplayName("one plain string, including one with commas, produces no parts either")
  void onePlainStringProducesNoParts() {
    final var prompt = "Be helpful, be concise, and be honest.";
    assertThat(resolvedParts(Map.of("app.ai.system-prompt", prompt))).isEmpty();
  }

  @Test
  @DisplayName("several literal pieces are resolved as separate parts, never joined")
  void severalLiteralPiecesStayApart() {
    final var parts =
        resolvedParts(
            Map.of(
                "app.ai.system-prompt[0]", "You work for Acme.",
                "app.ai.system-prompt[1]", "Always answer in Acme's house style."));
    assertThat(parts).containsExactly("You work for Acme.", "Always answer in Acme's house style.");
  }

  @Test
  @DisplayName("a classpath: element is read and its content becomes its own part")
  void classpathElementIsItsOwnPart() {
    final var parts =
        resolvedParts(
            Map.of(
                "app.ai.system-prompt[0]", "You work for Acme.",
                "app.ai.system-prompt[1]", "classpath:core/prompts/auto-memory.md"));
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0)).isEqualTo("You work for Acme.");
    assertThat(parts.get(1)).contains("You have a persistent, file-based memory");
  }

  @Test
  @DisplayName("a lone element that is a resource location is still resolved, not left as text")
  void loneResourceLocation() {
    final var parts =
        resolvedParts(Map.of("app.ai.system-prompt", "classpath:core/prompts/auto-memory.md"));
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0)).contains("You have a persistent, file-based memory");
  }

  @Test
  @DisplayName("${app.locale} in a resource location is expanded before the resource is read")
  void localePlaceholderExpanded() {
    final var parts =
        resolvedParts(
            Map.of(
                "app.locale", "zh_CN",
                "app.ai.system-prompt[0]", "classpath:core/prompts/system-prompt_${app.locale}.md",
                "app.ai.system-prompt[1]", "Extra house rule."));
    assertThat(parts).hasSize(2);
    // The Chinese file, not the English one, was read.
    assertThat(parts.get(0)).doesNotContain("You are a helpful AI assistant");
    assertThat(parts.get(1)).isEqualTo("Extra house rule.");
  }
}
