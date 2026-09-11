package me.kezhenxu94.springagent.appwebui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * That the four {@code spring.ai.model.*} switches in this application's {@code application.yaml}
 * land where Spring AI reads them.
 *
 * <p>Two of them are written as a dotted key — {@code embedding.text:} beside {@code embedding:} —
 * because they are sibling properties rather than a nested object: OpenAI's auto-configuration is
 * gated on {@code spring.ai.model.embedding} and Google's on {@code
 * spring.ai.model.embedding.text}, so both have to exist as scalars at once. YAML cannot express
 * that as a tree, and a block written at a nesting level Boot ignores fails in silence — the same
 * trap {@code DockerShellDefaultsTest} exists for, which is why this reads the file through Boot's
 * own loader rather than trusting it.
 *
 * <p>One of four identical copies, one per server application, and that is the point rather than an
 * oversight: the four {@code application.yaml} files are derived from the Feishu server's and have
 * to stay in step, a setting meaning the same thing in each. Nothing in the build enforces that, so
 * a per-file assertion of the shared block is what notices when one drifts.
 *
 * <p>Getting any of these wrong is not a subtle bug: with three providers on the classpath and
 * Spring AI's conditions all {@code matchIfMissing}, a kind that resolves to nothing gets every
 * provider and the application fails to start with two {@code ChatModel} beans.
 */
class ModelProviderSwitchesTest {

  private static Map<String, String> applicationYaml() throws Exception {
    // Every document, merged: the file carries profile sections after the main one, and which
    // document a key lands in is not what this is asserting.
    final var sources =
        new YamlPropertySourceLoader()
            .load("application.yaml", new ClassPathResource("application.yaml"));
    assertThat(sources).as("application.yaml should have loaded at all").isNotEmpty();
    final var properties = new java.util.LinkedHashMap<String, String>();
    for (final PropertySource<?> source : sources) {
      @SuppressWarnings("unchecked")
      final var document = (Map<String, Object>) source.getSource();
      // Values arrive as OriginTrackedCharSequence, which is not a String and does not equal one.
      // Flattened to text here so the assertions below read as what the file says.
      document.forEach((key, value) -> properties.put(key, String.valueOf(value)));
    }
    return properties;
  }

  @Test
  @DisplayName("all four switches are present as flat properties")
  void theSwitchesBind() throws Exception {
    final var properties = applicationYaml();

    assertThat(properties)
        .containsKeys(
            "spring.ai.model.chat",
            "spring.ai.model.embedding",
            // The dotted key. If YAML had folded this into the scalar above it, this assertion is
            // what notices — and the symptom in production would be two EmbeddingModel beans.
            "spring.ai.model.embedding.text",
            "spring.ai.model.image");
  }

  @Test
  @DisplayName("the two embedding switches default to the same value, from one variable")
  void theEmbeddingSwitchesAgree() throws Exception {
    final var properties = applicationYaml();

    // Naming only one of them silences one provider and leaves the other matching by default. They
    // read the same environment variable so that they cannot be set apart by accident.
    assertThat(properties.get("spring.ai.model.embedding"))
        .isEqualTo(properties.get("spring.ai.model.embedding.text"))
        .isEqualTo("${EMBEDDING_MODEL_PROVIDER:openai}");
  }

  @Test
  @DisplayName("image generation is off by default, unlike the CLI's")
  void imageDefaultsToNone() throws Exception {
    // A paid third-party API this deployment would start calling on the model's say-so. The same
    // reasoning as app.ai.tools.shell.type defaulting to none.
    assertThat(applicationYaml().get("spring.ai.model.image"))
        .isEqualTo("${IMAGE_MODEL_PROVIDER:none}");
  }

  @Test
  @DisplayName("the Gemini block is present and contributes nothing until a key is named")
  void geminiIsInertByDefault() throws Exception {
    final var properties = applicationYaml();

    assertThat(properties).containsEntry("spring.ai.google.genai.api-key", "${GEMINI_API_KEY:}");
    // No default for either model name: Gemini has none, and an empty name reads back from the
    // endpoint as a broken gateway. GoogleGenAiConnectionCheck fails startup instead.
    assertThat(properties)
        .containsEntry("spring.ai.google.genai.chat.model", "${GEMINI_CHAT_MODEL:}");
    assertThat(properties)
        .containsEntry("spring.ai.google.genai.embedding.text.model", "${GEMINI_EMBEDDING_MODEL:}");
    // The image model does have one, because GoogleGenAiImageOptions does.
    assertThat(properties)
        .containsEntry(
            "spring.ai.google.genai.image.model", "${GEMINI_IMAGE_MODEL:gemini-2.5-flash-image}");
  }

  @Test
  @DisplayName(
      "transcription is switchable, which is how a deployment without one turns the tool off")
  void transcriptionIsSwitchable() throws Exception {
    // Only one provider here serves transcription, so this key is not about choosing. Left at
    // openai on a deployment whose endpoint serves none — every google-genai-only one — core
    // registers TranscribeAudio against a client with nowhere to go.
    assertThat(applicationYaml())
        .containsEntry(
            "spring.ai.model.audio.transcription", "${TRANSCRIPTION_MODEL_PROVIDER:openai}");
  }

  @Test
  @DisplayName("every embedding dimension defaults to the same width, whichever provider is chosen")
  void theDimensionsAgree() throws Exception {
    final var properties = applicationYaml();

    // The failure this exists for is not subtle but its message is: Milvus answers
    // `Incorrect dimension for field 'embedding': the no.0 vector's dimension: 1536 is not equal
    // to field's dimension: 1024`, which reaches a user as a run dying on `Stream processing
    // failed` — naming neither embeddings nor the setting. Switching provider must not be able to
    // cause that on its own, so the three per-provider defaults and the two store widths all have
    // to be the same number out of the box.
    final var widths =
        java.util.List.of(
            "spring.ai.openai.embedding.dimensions",
            "spring.ai.dashscope.embedding.dimensions",
            "spring.ai.google.genai.embedding.text.dimensions",
            "spring.ai.vectorstore.milvus.embedding-dimension",
            "app.ai.rag.milvus.embedding-dimension");

    assertThat(widths)
        .allSatisfy(
            key ->
                assertThat(defaultOf(properties.get(key)))
                    .as("%s should default to the same width as every other", key)
                    .isEqualTo("1024"));
  }

  @Test
  @DisplayName("the tool index width is a variable, so a wider model is configurable at all")
  void theToolIndexWidthIsSettable() throws Exception {
    // It was a bare literal, which meant a deployment on a 1536-dimension model could not run this
    // application without editing the file it ships with.
    assertThat(applicationYaml())
        .containsEntry(
            "spring.ai.vectorstore.milvus.embedding-dimension",
            "${VECTORSTORE_MILVUS_DIMENSION:1024}");
  }

  /**
   * The default out of a {@code ${VAR:default}} placeholder, or the value where it is a literal.
   */
  private static String defaultOf(final String value) {
    if (value == null || !value.startsWith("${")) {
      return value;
    }
    final var colon = value.indexOf(':');
    return colon < 0 ? "" : value.substring(colon + 1, value.length() - 1);
  }
}
