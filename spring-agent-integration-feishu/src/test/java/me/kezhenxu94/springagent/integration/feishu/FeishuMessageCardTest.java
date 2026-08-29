package me.kezhenxu94.springagent.integration.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.stream.StreamSupport;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import me.kezhenxu94.springagent.integration.feishu.handler.FeishuCardElements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** What a message the agent did not stream looks like when it lands in the chat. */
class FeishuMessageCardTest {

  private final JsonMapper objectMapper = new JsonMapper();

  private FeishuMessageCard card;

  @BeforeEach
  void setUp() {
    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));
    card =
        new FeishuMessageCard(
            objectMapper,
            messages,
            new FeishuCardElements(
                objectMapper, messages, new ClassPathResource("feishu/card-elements.json"), null),
            new ClassPathResource("feishu/reply-card.json"));
  }

  @Test
  @DisplayName("the markdown is injected into the message element")
  void injectsMarkdown() throws Exception {
    final var json = card.render("hello **world**");

    final var elements = objectMapper.readTree(json).path("body").path("elements");
    final var message =
        StreamSupport.stream(elements.spliterator(), false)
            .filter(e -> "message".equals(e.path("element_id").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(message.path("content").asString()).isEqualTo("hello **world**");
  }

  @Test
  @DisplayName("streaming_mode is off, since there is nothing left to stream")
  void disablesStreamingMode() throws Exception {
    final var json = card.render("anything");

    assertThat(objectMapper.readTree(json).path("config").path("streaming_mode").asBoolean())
        .isFalse();
  }

  @Test
  @DisplayName("the stop button and the usage footer are stripped, having nothing to report")
  void stripsMessageActions() throws Exception {
    final var json = card.render("anything");

    final var elements = objectMapper.readTree(json).path("body").path("elements");
    assertThat(elements.isArray()).isTrue();
    final var elementIds =
        StreamSupport.stream(elements.spliterator(), false)
            .map(e -> e.path("element_id").asString())
            .toList();
    assertThat(elementIds).doesNotContain("message_actions", "usage");

    assertThat(json).doesNotContain("\"Stop\"").doesNotContain("\"element_id\":\"stop\"");
  }
}
