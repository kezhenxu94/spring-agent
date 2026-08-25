package me.kezhenxu94.springagent.integration.feishu.handler;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReactionReqBody;
import com.lark.oapi.service.im.v1.model.Emoji;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * What the agent puts on a message it cannot answer yet.
 *
 * <p>A message sent while a run is already working is queued onto that run rather than answered by
 * one of its own, and until the run reaches a point where it can read it, nothing happens: the card
 * above goes on streaming an answer to the previous message, and the person who just typed has no
 * way to tell their message was seen at all, or whether they should send it again. The card does
 * say so, but the card is a different message from theirs, and on a phone it is often not the one
 * on screen. A reaction is on the message itself, which is where they are looking.
 *
 * <p>Two of them, because there are two things worth knowing and they happen at different times:
 * {@code Get} the moment the message is queued, and {@code DONE} when the run has actually taken it
 * in. The first is left in place rather than removed — between them they read as a progression, and
 * a message still showing only {@code Get} when the run ends is a true statement about what
 * happened to it.
 *
 * <p>Failures are logged and dropped. A reaction is a courtesy on top of the run, and a run worth
 * less than its own decoration would be a poor trade.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuMessageReactions {

  /** Seen, and waiting for the run to reach a point where it can read it. */
  private static final String QUEUED = "Get";

  /** Read into the run, so the model is working with it now. */
  private static final String READ = "DONE";

  private final Client feishu;

  /** Marks {@code messageId} as seen. */
  public void queued(final String messageId) {
    react(messageId, QUEUED);
  }

  /** Marks {@code messageId} as taken in by the run. */
  public void read(final String messageId) {
    react(messageId, READ);
  }

  @SneakyThrows
  private void react(final String messageId, final String emoji) {
    if (messageId == null || messageId.isEmpty()) {
      return;
    }
    final var response =
        feishu
            .im()
            .v1()
            .messageReaction()
            .create(
                CreateMessageReactionReq.newBuilder()
                    .messageId(messageId)
                    .createMessageReactionReqBody(
                        CreateMessageReactionReqBody.newBuilder()
                            .reactionType(Emoji.newBuilder().emojiType(emoji).build())
                            .build())
                    .build());
    if (response.getCode() != 0) {
      log.warn(
          "Failed to react {} to message {}: code={}, msg={}",
          emoji,
          messageId,
          response.getCode(),
          response.getMsg());
    }
  }
}
