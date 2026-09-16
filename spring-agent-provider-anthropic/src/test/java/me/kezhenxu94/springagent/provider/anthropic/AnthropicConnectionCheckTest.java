package me.kezhenxu94.springagent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * That a deployment which got the configuration wrong is told so at startup, in terms of the things
 * it can change.
 *
 * <p>Each of these fails much later and much worse without the check. A missing API key is a 401 on
 * the first message. A missing Vertex project is an SDK message naming a builder field. A missing
 * model is the subtlest of the three and the reason this class is not optional: {@code
 * AnthropicChatOptions} carries a {@code DEFAULT_MODEL} and applies it silently, so the deployment
 * does not fail — it quietly runs on a model nobody chose, and on Vertex, where a model is dated
 * with {@code @} rather than a hyphen, that default is a name the project will refuse with a 404
 * that reads like a broken project.
 *
 * <p>There is a security-shaped reason too, and it is why the check throws rather than warns.
 * Spring AI's Anthropic client falls back to {@code ANTHROPIC_API_KEY} and {@code
 * ANTHROPIC_AUTH_TOKEN} in the <em>process</em> environment when the property is blank. A
 * deployment that named {@code anthropic} and forgot the key would therefore not fail at all on a
 * machine that exports one — it would work, and bill somebody else. Failing startup closes that
 * window.
 */
class AnthropicConnectionCheckTest {

  private static AnthropicConnectionCheck check(
      final String chatProvider,
      final String backend,
      final String apiKey,
      final String model,
      final AnthropicProperties.Vertex vertex) {
    final var environment = new MockEnvironment();
    environment.setProperty(AnthropicProperties.CHAT_PROVIDER_PROPERTY, chatProvider);
    environment.setProperty(AnthropicProperties.API_KEY_PROPERTY, apiKey);
    environment.setProperty(AnthropicProperties.CHAT_MODEL_PROPERTY, model);
    return new AnthropicConnectionCheck(new AnthropicProperties(backend, vertex), environment);
  }

  private static final AnthropicProperties.Vertex NO_VERTEX =
      new AnthropicProperties.Vertex(null, null, null);

  private static final AnthropicProperties.Vertex VERTEX =
      new AnthropicProperties.Vertex("a-project", "us-east5", null);

  @Test
  @DisplayName("a deployment that names another provider is not this module's business")
  void namingAnotherProviderIsFine() {
    assertThatCode(check("openai", "", "", "", NO_VERTEX)::check).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a backend nobody implements is refused rather than read as the default")
  void aTypoIsRefused() {
    // `vertexai` is not `vertex`, and quietly meaning "not vertex" would send the deployment to
    // Anthropic's API and fail on a missing key, naming neither the word they mistyped nor Vertex.
    assertThatThrownBy(check("anthropic", "vertexai", "sk-ant", "claude-sonnet-4-5", VERTEX)::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ANTHROPIC_BACKEND");
  }

  @Test
  @DisplayName("no model is refused, because Spring AI would substitute one nobody chose")
  void noModelIsRefused() {
    assertThatThrownBy(check("anthropic", "anthropic", "sk-ant", "", NO_VERTEX)::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ANTHROPIC_CHAT_MODEL");
  }

  @Test
  @DisplayName("on vertex, the refusal also says how a model is spelled there")
  void theVertexModelSpellingIsExplained() {
    assertThatThrownBy(check("anthropic", "vertex", "", "", VERTEX)::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("@");
  }

  @Test
  @DisplayName("no API key on the anthropic backend is refused")
  void noKeyIsRefused() {
    assertThatThrownBy(check("anthropic", "anthropic", "", "claude-sonnet-4-5", NO_VERTEX)::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ANTHROPIC_API_KEY");
  }

  @Test
  @DisplayName("a blank key is an unset key, not a configured one")
  void aBlankKeyIsUnset() {
    // A property spelled ${ANTHROPIC_API_KEY:} is present and empty when nobody set the variable,
    // and a key of one space is a key nobody set either.
    assertThatThrownBy(
            check("anthropic", "anthropic", "   ", "claude-sonnet-4-5", NO_VERTEX)::check)
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("vertex without a project or a location is refused")
  void vertexNeedsAProjectAndALocation() {
    assertThatThrownBy(
            check(
                    "anthropic",
                    "vertex",
                    "",
                    "claude-sonnet-4-5@20250929",
                    new AnthropicProperties.Vertex("a-project", null, null))
                ::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("VERTEX_LOCATION");
  }

  @Test
  @DisplayName("vertex needs no API key, which is the point of it")
  void vertexNeedsNoKey() {
    assertThatCode(check("anthropic", "vertex", "", "claude-sonnet-4-5@20250929", VERTEX)::check)
        .doesNotThrowAnyException();
  }
}
