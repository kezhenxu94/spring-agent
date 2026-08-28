package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * That the prompts the agent speaks with are supplied, and supplied in the workspace's language.
 *
 * <p>This is the test the tool checks could not be: they enumerate {@code @Tool} annotations and
 * ask whether each is translated, so they can only ever see tools. The system prompt is a
 * configuration property, five and a half thousand characters of it, and it stayed English through
 * every one of them being green — which is what a deployment reads as "nearly all of it is still
 * English", the prompt being larger than every tool description put together.
 */
class PromptDefaultsTest {

  private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fff]");

  /** Every placeholder the renderer will be asked to fill, so a render can be attempted at all. */
  private static final Set<String> VARIABLES =
      Set.of(
          "userId",
          "chatId",
          "chatType",
          "messageId",
          "threadId",
          "parentId",
          "mentions",
          "replyFormat",
          "homeDirs",
          "taskText");

  private static Map<String, Object> promptsIn(final String locale) {
    final var environment = new StandardEnvironment();
    if (locale != null) {
      environment
          .getPropertySources()
          .addFirst(new MapPropertySource("test", Map.of("app.locale", locale)));
    }
    new PromptDefaults().postProcessEnvironment(environment, new SpringApplication());
    final var supplied = new HashMap<String, Object>();
    for (final var prompt : PromptDefaults.PROMPTS) {
      supplied.put(prompt[1], environment.getProperty(prompt[1]));
    }
    return supplied;
  }

  @Test
  @DisplayName("all three prompts are supplied, so nothing falls back to a Java constant")
  void allThreeAreSupplied() {
    assertSoftly(
        softly ->
            promptsIn("en")
                .forEach(
                    (property, value) ->
                        softly.assertThat(value).as(property).isNotNull().asString().isNotBlank()));
  }

  /** The one that would have caught it: ask for Chinese, and check what is actually supplied. */
  @Test
  @DisplayName("a Chinese workspace is given Chinese prompts, not English ones")
  void chineseWorkspaceGetsChinese() {
    assertSoftly(
        softly ->
            promptsIn("zh_CN")
                .forEach(
                    (property, value) ->
                        softly
                            .assertThat(CHINESE.matcher(String.valueOf(value)).find())
                            .as("%s is still English", property)
                            .isTrue()));
  }

  @Test
  @DisplayName("a language nobody translated falls back to English rather than failing startup")
  void untranslatedLocale() {
    assertThatCode(() -> promptsIn("fr")).doesNotThrowAnyException();
    assertThat(promptsIn("fr")).isEqualTo(promptsIn("en"));
  }

  /**
   * A prompt is rendered through a brace-delimited template, so a stray brace or a renamed
   * placeholder fails the render — on every request, for every user of that language, rather than
   * in whatever wrote it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"en", "zh_CN"})
  @DisplayName("every translation renders, with every placeholder it uses being one we supply")
  void everyTranslationRenders(final String locale) {
    final var variables = new HashMap<String, Object>();
    VARIABLES.forEach(name -> variables.put(name, "x"));

    assertSoftly(
        softly ->
            promptsIn(locale)
                .forEach(
                    (property, value) ->
                        softly
                            .assertThatCode(
                                () ->
                                    new SystemPromptTemplate(String.valueOf(value))
                                        .render(variables))
                            .as("%s in %s", property, locale)
                            .doesNotThrowAnyException()));
  }

  /**
   * And that a translation carries the same placeholders as the English it replaces. Renders would
   * still pass if one were dropped — the model would simply never be told its own chat id.
   */
  @ParameterizedTest
  @ValueSource(strings = {"zh_CN"})
  @DisplayName("a translation drops none of the placeholders the English states")
  void everyTranslationKeepsItsPlaceholders(final String locale) {
    final var placeholders = Pattern.compile("\\{(\\w+)\\}");
    final var base = promptsIn("en");

    assertSoftly(
        softly ->
            promptsIn(locale)
                .forEach(
                    (property, value) -> {
                      final var expected = new java.util.HashSet<String>();
                      final var actual = new java.util.HashSet<String>();
                      var matcher = placeholders.matcher(String.valueOf(base.get(property)));
                      while (matcher.find()) {
                        expected.add(matcher.group(1));
                      }
                      matcher = placeholders.matcher(String.valueOf(value));
                      while (matcher.find()) {
                        actual.add(matcher.group(1));
                      }
                      softly
                          .assertThat(actual)
                          .as("%s in %s", property, locale)
                          .containsExactlyInAnyOrderElementsOf(expected);
                    }));
  }
}
