package me.kezhenxu94.springagent.provider.dashscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/**
 * What one DashScope credential turns into, and — the part worth guarding — what it leaves alone.
 *
 * <p>Against a bound environment rather than the yaml, because the yaml is not the contract: the
 * failure this protects against is a target already carrying an <em>empty</em> value, which is what
 * {@code ${OPENAI_API_KEY:}} leaves behind when nobody set the variable and what an ordinary
 * lowest-precedence property source cannot beat. See {@link DashScopeDefaults}.
 */
class DashScopeDefaultsTest {

  private static final String KEY = "sk-dashscope";

  private final DashScopeDefaults defaults = new DashScopeDefaults();

  private MockEnvironment process(final String... pairs) {
    final var environment = new MockEnvironment();
    for (int i = 0; i < pairs.length; i += 2) {
      environment.setProperty(pairs[i], pairs[i + 1]);
    }
    defaults.postProcessEnvironment(environment, new SpringApplication());
    return environment;
  }

  @Test
  @DisplayName("one key fills in the whole OpenAI-compatible connection")
  void fillsInTheConnection() {
    final var environment =
        process(
            "spring.ai.dashscope.api-key", KEY,
            "spring.ai.dashscope.chat.model", "qwen3.8-max",
            "spring.ai.dashscope.embedding.model", "text-embedding-v4",
            "spring.ai.dashscope.embedding.dimensions", "1024");

    assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo(KEY);
    assertThat(environment.getProperty("spring.ai.openai.base-url"))
        .isEqualTo(DashScopeProperties.DEFAULT_BASE_URL + DashScopeProperties.COMPATIBLE_MODE_PATH);
    assertThat(environment.getProperty("spring.ai.openai.chat.model")).isEqualTo("qwen3.8-max");
    assertThat(environment.getProperty("spring.ai.openai.embedding.api-key")).isEqualTo(KEY);
    assertThat(environment.getProperty("spring.ai.openai.embedding.base-url"))
        .isEqualTo(DashScopeProperties.DEFAULT_BASE_URL + DashScopeProperties.COMPATIBLE_MODE_PATH);
    assertThat(environment.getProperty("spring.ai.openai.embedding.model"))
        .isEqualTo("text-embedding-v4");
    assertThat(environment.getProperty("spring.ai.openai.embedding.dimensions")).isEqualTo("1024");
  }

  @Test
  @DisplayName("a host becomes the whole endpoint, because the OpenAI SDK appends nothing")
  void theHostBecomesTheWholeEndpoint() {
    final var environment =
        process(
            "spring.ai.dashscope.api-key",
            KEY,
            "spring.ai.dashscope.base-url",
            "https://ws-abc.cn-beijing.maas.aliyuncs.com");

    assertThat(environment.getProperty("spring.ai.openai.base-url"))
        .isEqualTo("https://ws-abc.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
  }

  @Test
  @DisplayName("a trailing slash on the host does not become a double slash in the path")
  void aTrailingSlashIsTrimmed() {
    final var environment =
        process(
            "spring.ai.dashscope.api-key",
            KEY,
            "spring.ai.dashscope.base-url",
            "https://ws-abc.cn-beijing.maas.aliyuncs.com/");

    assertThat(environment.getProperty("spring.ai.openai.base-url"))
        .isEqualTo("https://ws-abc.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
  }

  @Test
  @DisplayName("a model that names a host of its own gets that one, and the others do not")
  void aPerModelHostWins() {
    final var environment =
        process(
            "spring.ai.dashscope.api-key",
            KEY,
            "spring.ai.dashscope.base-url",
            "https://shared.example.com",
            "spring.ai.dashscope.embedding.base-url",
            "https://embeddings.example.com");

    assertThat(environment.getProperty("spring.ai.openai.base-url"))
        .isEqualTo("https://shared.example.com/compatible-mode/v1");
    assertThat(environment.getProperty("spring.ai.openai.embedding.base-url"))
        .isEqualTo("https://embeddings.example.com/compatible-mode/v1");
  }

  @Test
  @DisplayName("nothing is contributed until a DashScope key says there is a DashScope")
  void inertWithoutAKey() {
    assertThat(process().getProperty("spring.ai.openai.api-key")).isNull();
    assertThat(process("spring.ai.dashscope.api-key", "").getProperty("spring.ai.openai.base-url"))
        .isNull();
  }

  @Test
  @DisplayName("an explicitly configured OpenAI endpoint wins over the DashScope one")
  void doesNotOverrideWhatWasSet() {
    final var environment =
        process(
            "spring.ai.dashscope.api-key", KEY,
            "spring.ai.dashscope.chat.model", "qwen3.8-max",
            "spring.ai.openai.api-key", "sk-openai",
            "spring.ai.openai.base-url", "https://gateway.example.com/v1",
            "spring.ai.openai.chat.model", "gpt-5.6");

    assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("sk-openai");
    assertThat(environment.getProperty("spring.ai.openai.base-url"))
        .isEqualTo("https://gateway.example.com/v1");
    assertThat(environment.getProperty("spring.ai.openai.chat.model")).isEqualTo("gpt-5.6");
    // ... and the embedding connection, which that deployment did not name, is still filled in:
    // the check is per property, not per block.
    assertThat(environment.getProperty("spring.ai.openai.embedding.api-key")).isEqualTo(KEY);
  }

  @Test
  @DisplayName("a target left present but empty is a target nothing set")
  void anEmptyValueIsNotAValue() {
    // The whole reason this class checks rather than contributing as the lowest-precedence source:
    // every application.yaml here says ${OPENAI_API_KEY:}, so the property is present and blank on
    // a deployment that never set the variable.
    final var environment =
        process(
            "spring.ai.dashscope.api-key", KEY,
            "spring.ai.openai.api-key", "",
            "spring.ai.openai.base-url", "");

    assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo(KEY);
    assertThat(environment.getProperty("spring.ai.openai.base-url"))
        .isEqualTo(DashScopeProperties.DEFAULT_BASE_URL + DashScopeProperties.COMPATIBLE_MODE_PATH);
  }

  @Test
  @DisplayName("the transcription endpoint is deliberately not filled in")
  void leavesTranscriptionAlone() {
    // DashScope serves it, but under model names Spring AI's OpenAI defaults do not know, so a
    // host with no model would look configured and ask for something that is not there.
    final var environment = process("spring.ai.dashscope.api-key", KEY);

    assertThat(environment.getProperty("spring.ai.openai.audio.transcription.api-key")).isNull();
    assertThat(environment.getProperty("spring.ai.openai.audio.transcription.base-url")).isNull();
  }
}
