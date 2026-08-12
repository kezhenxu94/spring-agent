package me.kezhenxu94.springagent.integration.feishu.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties.CardText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The card template and these labels have to agree on placeholder names, and nothing else checks
 * that they do: a name that matches nothing leaves a literal {@code {generating}} in a live card.
 */
class FeishuCardTextTest {

  private final JsonMapper om = new JsonMapper();

  private String shippedCard() throws Exception {
    return new ClassPathResource("feishu/reply-card.json")
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private static CardText cardText(final String generating, final String stop) {
    return new CardText(generating, stop, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("the shipped card has every placeholder these labels can fill, and no others")
  void shippedCardPlaceholdersAllResolve() throws Exception {
    final var rendered = cardText(null, null).render(shippedCard());

    assertThat(rendered).doesNotContain("{generating}", "{stop}", "{conversationHint}");
    assertThat(rendered).contains("Generating...", "Stop", "carry on the conversation");
  }

  @Test
  @DisplayName("configured labels reach the card, in whatever language they are written")
  void configuredLabelsAreRendered() throws Exception {
    final var rendered = cardText("正在生成...", "停止").render(shippedCard());

    assertThat(rendered).contains("正在生成...", "停止");
    assertThat(rendered).doesNotContain("Generating...", "\"Stop\"");
    assertThat(om.readTree(rendered).path("body").path("elements").isArray()).isTrue();
  }

  @Test
  @DisplayName("a label carrying a quote or newline leaves the card parseable")
  void labelsAreJsonEscaped() throws Exception {
    final var rendered = cardText("say \"hi\"\nthen wait", null).render(shippedCard());

    final var card = om.readTree(rendered);
    assertThat(card.path("body").path("elements").isArray()).isTrue();
    assertThat(rendered).contains("say \\\"hi\\\"\\nthen wait");
  }

  @Test
  @DisplayName("a card that spells its labels out is left alone")
  void templateWithoutPlaceholdersIsUntouched() {
    final var ownCard = "{\"content\":\"我们自己的卡片\"}";

    assertThat(cardText("正在生成...", "停止").render(ownCard)).isEqualTo(ownCard);
  }
}
