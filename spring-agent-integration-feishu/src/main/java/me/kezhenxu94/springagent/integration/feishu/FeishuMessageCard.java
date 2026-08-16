package me.kezhenxu94.springagent.integration.feishu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reply card as a finished message rather than something to stream into: the same template the
 * agent's answer appears in, with its markdown already written and the parts that only make sense
 * while a run is going on — the streaming flag, the stop button, the token usage — taken out.
 *
 * <p>Shared so that everything the agent says arrives looking the same, whether the model sent it
 * with {@code FeishuSendMessage} or the runtime sent it on the model's behalf.
 */
@Component
public class FeishuMessageCard {

  private final JsonMapper objectMapper;
  private final FeishuMessages messages;
  private final Resource feishuReplyCard;

  // The template comes in through the constructor rather than a @Value field, which is what the
  // classes that inject it themselves have to do: a field is an injection point of its own, and
  // AOT writes a plain assignment for it that cannot target a final field.
  public FeishuMessageCard(
      final JsonMapper objectMapper,
      final FeishuMessages messages,
      @Value("${app.feishu.reply-card:classpath:/feishu/reply-card.json}")
          final Resource feishuReplyCard) {
    this.objectMapper = objectMapper;
    this.messages = messages;
    this.feishuReplyCard = feishuReplyCard;
  }

  /** The card JSON to send as an {@code interactive} message, carrying {@code markdown}. */
  public String render(final String markdown) throws IOException {
    final var card =
        (ObjectNode)
            objectMapper.readTree(
                messages.renderCard(feishuReplyCard.getContentAsString(StandardCharsets.UTF_8)));
    final var config = card.path("config");
    if (config instanceof ObjectNode configNode) {
      configNode.put("streaming_mode", false);
    }
    final var elements = (ArrayNode) card.path("body").path("elements");
    final var iterator = elements.iterator();
    while (iterator.hasNext()) {
      final var el = iterator.next();
      final var elementId = el.path("element_id").asString();
      if ("stop".equals(elementId) || "usage".equals(elementId)) {
        iterator.remove();
      } else if ("message".equals(elementId)) {
        ((ObjectNode) el).put("content", markdown);
      }
    }
    return objectMapper.writeValueAsString(card);
  }
}
