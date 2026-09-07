package me.kezhenxu94.springagent.provider.dashscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * That {@link DashScopeDefaults} actually runs late enough to see an application's own file, and
 * still beats what that file left blank.
 *
 * <p>A real {@code SpringApplication} rather than an {@code ApplicationContextRunner}, because a
 * runner does not invoke {@code EnvironmentPostProcessor}s at all — so the two things under test
 * here, being registered in {@code META-INF/spring.factories} and being ordered after Boot's config
 * data, are exactly what a runner cannot see. Get either wrong and every DashScope deployment falls
 * back to an empty OpenAI connection, with nothing logged.
 *
 * <p>{@code src/test/resources/application.yaml} is the file it reads; it carries the {@code
 * ${VAR:}} shape every application in this repository uses.
 */
class DashScopeDefaultsOrderingTest {

  /**
   * {@code run(args)} rather than {@code properties(...)}, and the difference is the test rather
   * than a detail of it. {@code properties(...)} becomes Boot's {@code defaultProperties}, the
   * <em>lowest</em>-precedence source of all — below the blank {@code application.yaml} beside this
   * test — so a value passed that way would lose to the blank and the "already set" case would
   * never be exercised. Command-line arguments are the highest, which is how a value that really
   * was set arrives.
   */
  private Environment environmentWith(final String... args) {
    try (var context =
        new SpringApplicationBuilder(NoBeans.class).web(WebApplicationType.NONE).run(args)) {
      // Read while the context is open, returned as a value: the environment outlives it either
      // way, but asserting on a closed context is a habit worth not forming.
      final var environment = context.getEnvironment();
      assertThat(environment).isNotNull();
      return environment;
    }
  }

  @Test
  @DisplayName("the one key reaches spring.ai.openai.* past a yaml that left it blank")
  void fillsInPastABlankYamlValue() {
    final var environment =
        environmentWith(
            "--spring.ai.dashscope.api-key=sk-dashscope",
            "--spring.ai.dashscope.chat.model=qwen3.8-max");

    assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("sk-dashscope");
    assertThat(environment.getProperty("spring.ai.openai.base-url"))
        .isEqualTo(DashScopeProperties.DEFAULT_BASE_URL + DashScopeProperties.COMPATIBLE_MODE_PATH);
    assertThat(environment.getProperty("spring.ai.openai.chat.model")).isEqualTo("qwen3.8-max");
  }

  @Test
  @DisplayName("a real OpenAI value in the same environment still wins")
  void doesNotOverrideARealValue() {
    final var environment =
        environmentWith(
            "--spring.ai.dashscope.api-key=sk-dashscope",
            "--spring.ai.openai.api-key=sk-openai",
            "--spring.ai.openai.base-url=https://gateway.example.com/v1");

    assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("sk-openai");
    assertThat(environment.getProperty("spring.ai.openai.base-url"))
        .isEqualTo("https://gateway.example.com/v1");
  }

  @Test
  @DisplayName("no DashScope key leaves the blank yaml exactly as it was")
  void inertWithoutAKey() {
    final var environment = environmentWith();

    assertThat(environment.getProperty("spring.ai.openai.api-key")).isEmpty();
    assertThat(environment.getProperty("spring.ai.openai.base-url")).isEmpty();
  }

  /**
   * Nothing but a context to hang the environment off; this module's beans need a model endpoint.
   */
  @Configuration(proxyBeanMethods = false)
  static class NoBeans {}
}
