package me.kezhenxu94.springagent.integration.feishu.greeting;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.SeenUpdate;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.core.dao.repo.SeenUpdateRepo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Says hello to somebody opening the chat, and tells somebody coming back what has changed since
 * they last looked.
 *
 * <p>Two Feishu events lead here and both ask the same question, so both call the same method.
 * {@code p2p_chat_create} fires the first time a person ever opens a chat with the bot; {@code
 * im.chat.access_event.bot_p2p_chat_entered_v1} fires every time they open it, including that first
 * one.
 *
 * <p><b>Having no record is what makes somebody new, not which event arrived.</b> Feishu documents
 * {@code p2p_chat_create} as a webhook event and this deployment listens over the long connection,
 * so it may never be delivered at all — reading first contact off that event would make the welcome
 * card depend on a delivery nobody here controls. Reading it off the absent row instead makes the
 * chat-entered event enough on its own, and the other one a nicety.
 *
 * <p>What a person is shown follows from that one number:
 *
 * <ul>
 *   <li>no row — they have never been greeted, so the welcome card;
 *   <li>a row behind the newest note — the notes above it, and only those;
 *   <li>a row that is level — nothing at all. Chat-entered fires every time the chat is opened, and
 *       a card each time would be the agent talking over the conversation it is there to have.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuGreetings {

  private final Client feishu;
  private final FeishuUpdates updates;
  private final FeishuGreetingCards cards;
  private final SeenUpdateRepo seenUpdateRepo;
  private final ProcessedMessageRepo processedMessageRepo;

  /**
   * The event has to be acknowledged inside Feishu's budget and rendering plus a send is not
   * bounded by anything this code controls. See {@code FeishuQuestionAnswerHandler} for why this
   * pool and not the scheduler's.
   */
  @Qualifier("applicationTaskExecutor")
  private final TaskExecutor taskExecutor;

  /** Called from the event thread; returns as soon as the work is handed off. */
  public void greet(final String chatId, final String userId) {
    if (chatId == null || userId == null) {
      log.warn("A chat event arrived naming no chat or no user; nothing to greet");
      return;
    }
    taskExecutor.execute(() -> send(chatId, userId));
  }

  private void send(final String chatId, final String userId) {
    final var current = updates.current();
    final var seen = seenUpdateRepo.findById(userId).orElse(null);
    if (seen != null && seen.version() >= current) {
      return;
    }
    // Chat-entered fires on every open, and one person opening the chat on a phone and a laptop at
    // the same moment is two events, possibly at two replicas. Keyed on the person and the version
    // they are about to be brought up to, this is the atomic first-caller-wins that already exists
    // for exactly this shape of work — see ProcessedMessageRepo.claim. The row below is the record
    // of what was read; the claim only settles the race.
    final var claim = "feishu-greeting:" + userId + ":" + current;
    if (!processedMessageRepo.claim(claim)) {
      return;
    }
    try {
      final var card = seen == null ? cards.welcome() : cards.update(updates.since(seen.version()));
      create(chatId, card);
      seenUpdateRepo.save(
          SeenUpdate.builder().id(userId).version(current).updatedAt(Instant.now()).build());
      log.info("Greeted {} in {}, now at update {}", userId, chatId, current);
    } catch (Exception e) {
      // Released, because nothing was said: holding it would leave this person permanently one
      // version behind, and every later note unread along with the one that failed.
      processedMessageRepo.release(claim);
      log.error("Failed to greet {} in {}", userId, chatId, e);
    }
  }

  private void create(final String chatId, final String card) throws Exception {
    final var response =
        feishu
            .im()
            .v1()
            .message()
            .create(
                CreateMessageReq.newBuilder()
                    .receiveIdType("chat_id")
                    .createMessageReqBody(
                        CreateMessageReqBody.newBuilder()
                            .receiveId(chatId)
                            .msgType("interactive")
                            .content(card)
                            .build())
                    .build());
    if (response.getCode() != 0) {
      throw new IllegalStateException(
          "Could not send a greeting to " + chatId + ": " + response.getMsg());
    }
  }
}
