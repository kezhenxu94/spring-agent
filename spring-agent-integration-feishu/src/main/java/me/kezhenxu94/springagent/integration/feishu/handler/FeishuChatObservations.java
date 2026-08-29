package me.kezhenxu94.springagent.integration.feishu.handler;

import com.google.common.base.Strings;
import com.lark.oapi.service.im.v1.model.EventMessage;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.observing.EventIntake;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.core.observing.Route;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import me.kezhenxu94.springagent.integration.feishu.model.MessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.PostMessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.TextMessageContent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reports a group chat message the bot was not addressed in, so that the agent can watch a
 * conversation it is not part of and later decide whether it has anything worth saying.
 *
 * <p>A class of its own rather than more of {@link FeishuMessageReceiveHandler}, which is already
 * the largest thing in this module and is about answering a message. This is the opposite case —
 * the message that is not being answered — and keeping it apart is what makes it obvious at the
 * call site that nothing here starts a run.
 *
 * <p>Reached through an {@link ObjectProvider}, so this module keeps its compile dependency on core
 * alone: {@link EventIntake} is a core SPI with no implementation in core, and a deployment without
 * {@code spring-agent-events} on the classpath simply observes nothing. That is the default, not a
 * degraded mode.
 *
 * <p>What it promises the funnel is at-least-once with a stable delivery id, and no more than that.
 * Feishu redelivers an event it has not heard the acknowledgement for and a reconnecting connection
 * replays one, so the same message can be reported twice; the message id it is reported under does
 * not change between those attempts, which is the whole of what a transport has to get right.
 * Recognising the second attempt for what it is belongs to {@code EventIntake}, which does it once
 * for every source rather than once per transport in a key namespace of its own.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuChatObservations {

  /**
   * The name a deployment configures policy for these observations under, and the same literal as
   * {@code EventsProperties.FEISHU_CHAT} in {@code spring-agent-events}.
   *
   * <p>Textually coupled rather than shared, because the dependency may only point from a transport
   * to core and this is not core's business to name — the same arrangement as the {@code afterName}
   * string in {@code KnowledgeToolsConfiguration}. Change one and the other stops selecting the
   * settings it was written for, silently, so change both.
   */
  public static final String SOURCE = "feishu-chat";

  final JsonMapper om;
  final FeishuProperties feishuProperties;

  /**
   * The provider, not the bean: resolved per call rather than at construction so that this
   * component starts in a context where nothing implements the SPI, which is most of them.
   */
  final ObjectProvider<EventIntake> eventIntake;

  /**
   * Reports {@code message} as something seen in the chat it came from, if that chat is watched at
   * all.
   *
   * <p>Never throws. This is called from the Lark websocket event dispatcher, on the path that
   * acknowledges the message: an exception escaping here would be read by Feishu as the event not
   * having been delivered, and the same message would arrive again — so a funnel that is broken,
   * slow to fail, or absent costs an observation and nothing else.
   *
   * @param senderOpenId who spoke, recorded as part of the evidence rather than as an identity a
   *     later run acts as
   * @param tenantKey the sender's tenant, for scoping a run that talks back
   */
  public void observed(
      final EventMessage message, final String senderOpenId, final String tenantKey) {
    try {
      final var intake = eventIntake.getIfAvailable();
      if (intake == null) {
        return;
      }

      final var chatId = message.getChatId();
      // Every message in every group the bot sits in would otherwise become a stored row and, in
      // time, something shown to a model — a volume and a privacy decision that belongs to whoever
      // runs the deployment, so watching is off until a chat is named. Checked before anything
      // leaves this thread, so that an unwatched chat leaves no trace anywhere.
      if (chatId == null || !feishuProperties.observedChatIds().contains(chatId)) {
        return;
      }

      // Only what the message itself says, read out of the event. Deliberately not
      // FeishuMessageReceiveHandler#addToChat: that turns an image or a file into text by
      // downloading it into a user's workspace, which is minutes of work and a write to disk for a
      // message nobody asked about — and it happens on this very thread, where Feishu is waiting
      // for the acknowledgement. A message carrying no text is therefore not observed at all
      // rather than observed as the bare fact that something arrived, which no run could act on.
      final var text = textOf(message.getMessageType(), message.getContent());
      if (Strings.isNullOrEmpty(text)) {
        return;
      }

      intake.observe(
          Observation.builder()
              .source(SOURCE)
              // Feishu's own message id, unprefixed. It is stable across a redelivery of the same
              // message and different for the next one, which is what the funnel needs of a
              // delivery id, and the funnel namespaces it by source when it claims one — so a
              // prefix here would only spell the source twice.
              .deliveryId(message.getMessageId())
              .kind("chat.message")
              // One rolling window per chat, not per topic. Deciding that two messages are about
              // the same thing would take embeddings and a threshold, and would be wrong often
              // enough to split a conversation in half; a chat is a grouping that is right by
              // construction, and how much of it is worth reasoning about is a question for
              // whatever reads the window later.
              .correlationKey(SOURCE + ":" + chatId)
              .title("Feishu group chat " + chatId)
              // Who spoke belongs here: Observation has no field for it on purpose, because the
              // speaker is evidence and must never be mistaken for the identity a run about this
              // acts as.
              .summary(senderOpenId + " said: " + text)
              .payloadJson(message.getContent())
              .route(
                  Route.builder()
                      .chatId(chatId)
                      .chatType(message.getChatType())
                      .groupId(chatId)
                      .tenantId(tenantKey)
                      .build())
              .build());
    } catch (Exception e) {
      log.warn(
          "Failed to observe message {} in chat {}",
          message.getMessageId(),
          message.getChatId(),
          e);
    }
  }

  /**
   * What the message says, or null where it says nothing that can be read without downloading it.
   *
   * <p>Not {@code FeishuMessageReceiveHandler#displayOf}, which answers the narrower question of
   * what one line of a card can show and so keeps only a post's title. An observation is read as
   * evidence rather than glanced at, so a post contributes its body too.
   */
  private String textOf(final String messageType, final String content) {
    if (content == null) {
      return null;
    }
    try {
      return switch (om.readValue(content, MessageContent.class)) {
        case TextMessageContent text -> text.text();
        case PostMessageContent post ->
            post.content().stream()
                .flatMap(c -> c.stream())
                .map(it -> it.text())
                .filter(Predicate.not(Strings::isNullOrEmpty))
                .collect(Collectors.joining(" ", Strings.nullToEmpty(post.title()) + "\n", ""));
        default -> null;
      };
    } catch (Exception e) {
      log.warn("Could not read what {} message says, not observing it", messageType, e);
      return null;
    }
  }
}
