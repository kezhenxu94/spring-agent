package me.kezhenxu94.springagent.provider.googlegenai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * That a misconfiguration is named at startup, in terms of the two things whoever deployed this can
 * change: the variable and the switch.
 *
 * <p>Both failure modes it guards are silent otherwise. Naming {@code google-genai} with no key
 * means {@link GoogleGenAiAutoConfigurationFilter} removed every Google GenAI auto-configuration,
 * so the application starts with no model of that kind and fails much later about a missing bean.
 * Setting a key but naming no model means a request for a model called {@code ""}, which the
 * endpoint answers with something that reads like a broken gateway.
 */
class GoogleGenAiConnectionCheckTest {

  private static final String KEY = GoogleGenAiProperties.API_KEY_PROPERTY;

  private static void check(final MockEnvironment environment) {
    final var properties =
        new GoogleGenAiProperties(
            environment.getProperty(KEY),
            new GoogleGenAiProperties.Chat(
                environment.getProperty(GoogleGenAiProperties.PREFIX + ".chat.model")),
            new GoogleGenAiProperties.Embedding(
                new GoogleGenAiProperties.Embedding.Text(
                    environment.getProperty(GoogleGenAiProperties.PREFIX + ".embedding.text.model"),
                    null)),
            new GoogleGenAiProperties.Image(
                environment.getProperty(GoogleGenAiProperties.PREFIX + ".image.model")));
    new GoogleGenAiConnectionCheck(properties, environment).check();
  }

  @Test
  @DisplayName("a deployment that names no kind of model is not a misconfiguration")
  void namingNothingIsFine() {
    // Carrying the module without using it has to be free — that is the whole premise of the
    // import filter, and this is the same premise on the other side of it.
    assertThatCode(() -> check(new MockEnvironment().withProperty(KEY, "AIza-test")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("naming google-genai with no key fails, naming both the variable and the switch")
  void selectedWithNoKey() {
    assertThatThrownBy(
            () ->
                check(
                    new MockEnvironment()
                        .withProperty(
                            GoogleGenAiProperties.CHAT_PROVIDER_PROPERTY, "google-genai")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.ai.model.chat")
        .hasMessageContaining(KEY)
        .hasMessageContaining("GEMINI_API_KEY");
  }

  @Test
  @DisplayName("a present-but-empty key is no key")
  void selectedWithABlankKey() {
    assertThatThrownBy(
            () ->
                check(
                    new MockEnvironment()
                        .withProperty(KEY, "")
                        .withProperty(
                            GoogleGenAiProperties.IMAGE_PROVIDER_PROPERTY, "google-genai")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.ai.model.image");
  }

  @Test
  @DisplayName("chat selected with no chat model fails on its own")
  void chatWithNoModel() {
    assertThatThrownBy(
            () ->
                check(
                    new MockEnvironment()
                        .withProperty(KEY, "AIza-test")
                        .withProperty(
                            GoogleGenAiProperties.CHAT_PROVIDER_PROPERTY, "google-genai")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(GoogleGenAiProperties.PREFIX + ".chat.model")
        .hasMessageContaining("GEMINI_CHAT_MODEL");
  }

  @Test
  @DisplayName("embeddings selected with no embedding model fails on its own")
  void embeddingsWithNoModel() {
    assertThatThrownBy(
            () ->
                check(
                    new MockEnvironment()
                        .withProperty(KEY, "AIza-test")
                        .withProperty(
                            GoogleGenAiProperties.EMBEDDING_PROVIDER_PROPERTY, "google-genai")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(GoogleGenAiProperties.PREFIX + ".embedding.text.model");
  }

  @Test
  @DisplayName("an image model need not be named, because that one has a default")
  void imagesNeedNoModel() {
    // GoogleGenAiImageOptions defaults to gemini-2.5-flash-image, unlike chat and embeddings where
    // the endpoint has no default and an empty name is refused.
    assertThatCode(
            () ->
                check(
                    new MockEnvironment()
                        .withProperty(KEY, "AIza-test")
                        .withProperty(
                            GoogleGenAiProperties.IMAGE_PROVIDER_PROPERTY, "google-genai")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a fully configured deployment starts")
  void configured() {
    assertThatCode(
            () ->
                check(
                    new MockEnvironment()
                        .withProperty(KEY, "AIza-test")
                        .withProperty(GoogleGenAiProperties.CHAT_PROVIDER_PROPERTY, "google-genai")
                        .withProperty(
                            GoogleGenAiProperties.EMBEDDING_PROVIDER_PROPERTY, "google-genai")
                        .withProperty(GoogleGenAiProperties.IMAGE_PROVIDER_PROPERTY, "google-genai")
                        .withProperty(
                            GoogleGenAiProperties.PREFIX + ".chat.model", "gemini-2.5-pro")
                        .withProperty(
                            GoogleGenAiProperties.PREFIX + ".embedding.text.model",
                            "gemini-embedding-001")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Vertex AI is refused rather than half-supported")
  void vertexIsRefused() {
    // The filter keys off the API key alone, so a deployment that configured Vertex correctly would
    // otherwise get no models and no explanation.
    assertThatThrownBy(
            () ->
                check(
                    new MockEnvironment()
                        .withProperty(KEY, "AIza-test")
                        .withProperty(GoogleGenAiProperties.CHAT_PROVIDER_PROPERTY, "google-genai")
                        .withProperty(GoogleGenAiProperties.PREFIX + ".vertex-ai", "true")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Vertex AI");

    assertThatThrownBy(
            () ->
                check(
                    new MockEnvironment()
                        .withProperty(KEY, "AIza-test")
                        .withProperty(GoogleGenAiProperties.CHAT_PROVIDER_PROPERTY, "google-genai")
                        .withProperty(GoogleGenAiProperties.PREFIX + ".project-id", "a-project")
                        .withProperty(GoogleGenAiProperties.PREFIX + ".location", "us-central1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Vertex AI");
  }
}
