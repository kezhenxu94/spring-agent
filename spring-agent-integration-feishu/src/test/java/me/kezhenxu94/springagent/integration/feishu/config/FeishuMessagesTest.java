package me.kezhenxu94.springagent.integration.feishu.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The card templates and these messages have to agree on placeholder names, and nothing else checks
 * that they do: a name that matches nothing leaves a literal {@code {stop}} in a live card.
 */
class FeishuMessagesTest {

  private static final Locale CHINESE = Locale.of("zh", "CN");

  private final JsonMapper om = new JsonMapper();

  static FeishuMessages messagesIn(final Locale locale) {
    return new FeishuMessages(
        new FeishuProperties(null, null, null, null, null, null, null, locale, null));
  }

  private static String shipped(final String template) throws Exception {
    return new ClassPathResource(template).getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void speaksTheLocaleItWasGiven() {
    assertThat(messagesIn(Locale.ENGLISH).get("card-stop")).isEqualTo("Stop");
    assertThat(messagesIn(CHINESE).get("card-stop")).isEqualTo("停止");
  }

  @Test
  void fallsBackToEnglishForALanguageThatDoesNotShip() {
    assertThat(messagesIn(Locale.JAPANESE).get("card-stop")).isEqualTo("Stop");
  }

  @Test
  void fillsInArguments() {
    assertThat(messagesIn(Locale.ENGLISH).error("no route to host"))
        .isEqualTo("Something went wrong: no route to host");
    assertThat(messagesIn(CHINESE).error("no route to host")).contains("no route to host");
  }

  @Test
  @DisplayName("a failure with no message of its own still says something")
  void errorWithoutAMessage() {
    assertThat(messagesIn(Locale.ENGLISH).error(null))
        .isEqualTo("Something went wrong: Unknown" + " error");
  }

  @Test
  void answersWithTheKeyRatherThanThrowingOnOneItDoesNotHave() {
    assertThat(messagesIn(Locale.ENGLISH).get("no-such-key")).isEqualTo("no-such-key");
  }

  @Test
  @DisplayName("the shipped card has every placeholder these messages can fill, and no others")
  void shippedCardPlaceholdersAllResolve() throws Exception {
    final var messages = messagesIn(Locale.ENGLISH);
    final var card = messages.renderCard(shipped("feishu/reply-card.json"));

    assertThat(card).doesNotContain("{conversationHint}");
    assertThat(card).contains("carry on the conversation");

    // The stop button is labelled in the elements file, not the card: it is one of the elements a
    // run puts on the card and takes off again, and it is rendered wherever it is read from.
    final var elements = messages.renderCard(shipped("feishu/card-elements.json"));

    assertThat(elements).doesNotContain("{stop}");
    assertThat(elements).contains("Stop");
  }

  @Test
  @DisplayName("the shipped question form has every placeholder these messages can fill")
  void shippedFormPlaceholdersAllResolve() throws Exception {
    final var rendered =
        messagesIn(Locale.ENGLISH).renderQuestionForm(shipped("feishu/question-form.json"));

    assertThat(rendered).doesNotContain("{selectHint}", "{otherHint}", "{submitText}");
    assertThat(rendered).contains("Pick an option", "type your own answer", "Submit");
  }

  @Test
  @DisplayName("a translated card is rendered in the language it was asked for")
  void translatedLabelsAreRendered() throws Exception {
    final var messages = messagesIn(CHINESE);
    final var card = messages.renderCard(shipped("feishu/reply-card.json"));

    assertThat(card).contains("回复这条消息").doesNotContain("{conversationHint}");
    assertThat(om.readTree(card).path("body").path("elements").isArray()).isTrue();

    final var elements = messages.renderCard(shipped("feishu/card-elements.json"));

    assertThat(elements).contains("停止").doesNotContain("\"Stop\"");
  }

  @Test
  @DisplayName("a label carrying a quote or newline leaves the card parseable")
  void labelsAreJsonEscaped() {
    assertThat(FeishuMessages.jsonEscaped("say \"hi\"\nthen wait"))
        .isEqualTo("say \\\"hi\\\"\\nthen wait");
  }

  @Test
  @DisplayName("a card that spells its labels out is left alone")
  void templateWithoutPlaceholdersIsUntouched() {
    final var ownCard = "{\"content\":\"我们自己的卡片\"}";

    assertThat(messagesIn(CHINESE).renderCard(ownCard)).isEqualTo(ownCard);
  }

  @Test
  void everyTranslationCoversEveryKey() throws IOException {
    final var english = keysOf("feishu/messages.properties");
    final var chinese = keysOf("feishu/messages_zh_CN.properties");

    assertThat(chinese).containsExactlyInAnyOrderElementsOf(english);
  }

  private static Set<String> keysOf(final String bundle) throws IOException {
    final var properties = new Properties();
    try (var stream = FeishuMessagesTest.class.getClassLoader().getResourceAsStream(bundle)) {
      assertThat(stream).as(bundle).isNotNull();
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
    return properties.stringPropertyNames();
  }
}
