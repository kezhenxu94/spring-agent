package me.kezhenxu94.springagent.integration.feishu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.handler.FeishuCardElements;
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
@RequiredArgsConstructor
public class FeishuMessageCard {

  private final JsonMapper objectMapper;
  private final FeishuMessages messages;

  /**
   * Where the element the markdown goes into comes from: the card template no longer ships one,
   * since a run streaming into the card adds it as it writes its first word. Taken from the same
   * place that run takes it, so an answer sent whole looks like an answer that was streamed.
   */
  private final FeishuCardElements elements;

  // The template arrives as a constructor argument, not through a @Value field the way the classes
  // that read it for themselves have to do it: a field is an injection point of its own, and AOT
  // writes a plain assignment for it that cannot target a final field. This stays final because
  // lombok.config lists @Value as copyable, so the generated constructor carries it to the
  // parameter — remove that line and this silently becomes an unresolved placeholder.
  @Value("${app.feishu.reply-card:classpath:/feishu/reply-card.json}")
  private final Resource feishuReplyCard;

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
    // Neither is in the shipped template — a card gains both as it runs, and this one never ran —
    // but a deployment's own card may still carry them, and a dead stop button on a finished
    // message is worse than one that was never there.
    final var body = (ArrayNode) card.path("body").path("elements");
    final var iterator = body.iterator();
    while (iterator.hasNext()) {
      final var elementId = iterator.next().path("element_id").asString();
      if (FeishuCardElements.STOP.equals(elementId) || "usage".equals(elementId)) {
        iterator.remove();
      }
    }
    body.insert(0, elements.element(FeishuCardElements.MESSAGE).put("content", markdown));
    return objectMapper.writeValueAsString(card);
  }
}
