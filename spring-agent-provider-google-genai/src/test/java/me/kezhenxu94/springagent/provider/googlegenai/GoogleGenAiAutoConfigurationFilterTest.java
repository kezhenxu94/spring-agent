package me.kezhenxu94.springagent.provider.googlegenai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.image.GoogleGenAiImageConnectionDetails;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.image.GoogleGenAiImageAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.image.GoogleGenAiImageConnectionAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The regression this module would otherwise be: carrying Google GenAI must cost a deployment that
 * has no Gemini key exactly nothing.
 *
 * <p>{@code GoogleGenAiImageConnectionAutoConfiguration} and its embedding twin are ungated — only
 * {@code @ConditionalOnClass} — and their eager {@code @Bean} methods throw when no credential is
 * configured. Without {@link GoogleGenAiAutoConfigurationFilter} that is a startup failure for
 * every existing deployment, none of which asked for Gemini.
 *
 * <p>An {@code ApplicationContextRunner} is the right tool here despite not invoking Boot's
 * auto-configuration import machinery, because these tests give the auto-configurations to the
 * runner directly and call the filter's own {@code match} beside them: the first half proves what
 * the filter decides, the second proves what happens to a context when it is not applied. Between
 * them they pin both the decision and the consequence, which one alone would not.
 */
class GoogleGenAiAutoConfigurationFilterTest {

  private static final String KEY = GoogleGenAiProperties.API_KEY_PROPERTY;

  private final GoogleGenAiAutoConfigurationFilter filter =
      new GoogleGenAiAutoConfigurationFilter();

  private static final String[] CANDIDATES =
      GoogleGenAiAutoConfigurationFilter.FILTERED.toArray(String[]::new);

  @Test
  @DisplayName("every name it filters is a class that is actually on the classpath")
  void theNamesAreReal() {
    // Named as strings so that filtering one does not load it, which means a rename upstream would
    // silently stop the filtering rather than fail. This is what notices.
    assertThat(GoogleGenAiAutoConfigurationFilter.FILTERED)
        .allSatisfy(
            name ->
                assertThat(Class.forName(name))
                    .as("%s should exist; if Spring AI renamed it, the filter is now inert", name)
                    .isNotNull());
    assertThat(GoogleGenAiAutoConfigurationFilter.FILTERED)
        .containsExactlyInAnyOrder(
            GoogleGenAiChatAutoConfiguration.class.getName(),
            GoogleGenAiEmbeddingConnectionAutoConfiguration.class.getName(),
            GoogleGenAiTextEmbeddingAutoConfiguration.class.getName(),
            GoogleGenAiImageConnectionAutoConfiguration.class.getName(),
            GoogleGenAiImageAutoConfiguration.class.getName());
  }

  @Test
  @DisplayName("with no key, every Google GenAI auto-configuration is removed")
  void noKeyFiltersEverything() {
    filter.setEnvironment(new org.springframework.mock.env.MockEnvironment());
    assertThat(filter.match(CANDIDATES, null)).containsOnly(false);
  }

  @Test
  @DisplayName("a present-but-empty key is not a key")
  void aBlankKeyFiltersEverything() {
    // The case @ConditionalOnProperty gets wrong, and the reason this reads the value rather than
    // asking whether the property exists: every application.yaml here spells it ${GEMINI_API_KEY:}.
    filter.setEnvironment(new org.springframework.mock.env.MockEnvironment().withProperty(KEY, ""));
    assertThat(filter.match(CANDIDATES, null)).containsOnly(false);

    filter.setEnvironment(
        new org.springframework.mock.env.MockEnvironment().withProperty(KEY, "   "));
    assertThat(filter.match(CANDIDATES, null)).containsOnly(false);
  }

  @Test
  @DisplayName("with a key, every one of them is kept")
  void aKeyKeepsEverything() {
    filter.setEnvironment(
        new org.springframework.mock.env.MockEnvironment().withProperty(KEY, "AIza-test"));
    assertThat(filter.match(CANDIDATES, null)).containsOnly(true);
  }

  @Test
  @DisplayName("somebody else's auto-configuration is never touched")
  void othersAreLeftAlone() {
    filter.setEnvironment(new org.springframework.mock.env.MockEnvironment());
    final var mixed =
        new String[] {
          "com.example.SomebodyElsesAutoConfiguration",
          GoogleGenAiChatAutoConfiguration.class.getName(),
          null
        };
    // null is what Boot leaves behind where an earlier filter removed an entry; it must be passed
    // through rather than treated as one of ours.
    assertThat(filter.match(mixed, null)).containsExactly(true, false, true);
  }

  @Test
  @DisplayName("unfiltered and unconfigured, the image connection really does fail startup")
  void theFailureBeingPreventedIsReal() {
    // The whole justification for the filter, asserted rather than asserted-in-a-comment. If Spring
    // AI ever gates these itself, this test fails and the filter can go.
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(GoogleGenAiImageConnectionAutoConfiguration.class))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("Incomplete Google GenAI configuration"));
  }

  @Test
  @DisplayName("with a key it builds the connection it would otherwise have failed on")
  void aKeyMakesThatSameContextStart() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(GoogleGenAiImageConnectionAutoConfiguration.class))
        .withPropertyValues(KEY + "=AIza-test")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(GoogleGenAiImageConnectionDetails.class));
  }

  @Test
  @DisplayName("a chat model is built only where a key says there is an endpoint")
  void theChatModelFollowsTheKey() {
    final var runner =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration
                        .class,
                    GoogleGenAiChatAutoConfiguration.class));

    runner
        .withPropertyValues(KEY + "=AIza-test", "spring.ai.google.genai.chat.model=gemini-2.5-pro")
        .run(context -> assertThat(context).hasSingleBean(GoogleGenAiChatModel.class));
  }
}
