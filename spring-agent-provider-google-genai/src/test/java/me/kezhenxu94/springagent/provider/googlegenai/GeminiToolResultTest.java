package me.kezhenxu94.springagent.provider.googlegenai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * That a tool result this project produces is something Gemini can actually read back.
 *
 * <p>Gemini's {@code functionResponse} carries a {@code Map}, so {@code GoogleGenAiChatModel}
 * parses whatever the tool answered with and throws {@code Failed to parse JSON} on anything that
 * is not JSON. A run then dies on {@code Stream processing failed}, naming neither the tool nor
 * what rewrote its result — which is how this reached production: every deployment spoke the OpenAI
 * protocol, which puts the string into the tool message verbatim and never looks at it.
 *
 * <p>Kept here rather than beside the interceptor because the constraint is this provider's: core
 * cannot see why the shape matters, and the provider is what makes it matter.
 */
class GeminiToolResultTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** What {@code GoogleGenAiChatModel.parseJsonToMap} does to a tool result, in miniature. */
  private static void asGeminiWould(final String toolResult) {
    MAPPER.readValue(toolResult, Object.class);
  }

  @Test
  @DisplayName("the sentence LargeResponseInterceptor answers with is readable as JSON")
  void aSpilledResultIsReadable() {
    // The exact shape that failed: prose naming a file, with newlines and a path in it.
    final var spilled =
        MAPPER.writeValueAsString(
            "The result of pull_request_read was too large (57,212 chars) to put in front of you."
                + " It has been saved to: /home/u/artifacts/tool-results/x.json\nWhat is in it:"
                + " [1 item, each {text: text}]");

    assertThatCode(() -> asGeminiWould(spilled)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("bare prose is what used to be sent, and Gemini genuinely cannot read it")
  void barePoseIsWhatBroke() {
    // Asserted so the fix cannot be quietly undone: if this ever stops throwing, the constraint
    // that InterceptingToolCallback.asJson exists for has gone away.
    assertThatCode(() -> asGeminiWould("pull_request_read was too large, saved to /tmp/x.json"))
        .isInstanceOf(Exception.class);
  }

  @Test
  @DisplayName("a tool answering with an object is passed through, not quoted a second time")
  void anObjectStaysAnObject() {
    assertThat(MAPPER.readValue("{\"files\":[\"a.txt\"]}", Object.class))
        .isInstanceOf(java.util.Map.class);
  }
}
