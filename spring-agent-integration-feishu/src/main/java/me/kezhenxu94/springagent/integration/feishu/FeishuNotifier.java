package me.kezhenxu94.springagent.integration.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.notify.Notifier;
import me.kezhenxu94.springagent.core.observing.Route;
import org.springframework.stereotype.Component;

/**
 * Says something to a Feishu chat with no run behind it — this deployment's implementation of
 * {@link Notifier}.
 *
 * <p>The same card {@code FeishuSendMessage} sends, so a notice looks like everything else the
 * agent says even though the agent did not write it. What it carries is either text this codebase
 * wrote about its own workings or a run's answer arriving from another surface — see {@link
 * Notifier} for both cases. Nothing from an event payload reaches here, and anything a person wrote
 * goes through {@link #quoted(String)} on the way.
 *
 * <p>Deliberately independent of a run, a request and the model. Its whole reason for existing is
 * the case where one of those is what failed — see {@link Notifier} — so it holds nothing but the
 * client and the card renderer, and takes no tool context.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuNotifier implements Notifier {

  private final Client feishu;
  private final FeishuMessageCard messageCard;

  @Override
  public void send(final Route route, final String text) {
    send(route, null, text);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Threaded with {@code im.message.reply}, which is the same call {@code FeishuCardListener}
   * makes to put a run's card under the message that started it — so a mirrored answer arrives in
   * the thread a reader is already following rather than at the bottom of the chat.
   *
   * <p>Whether {@code inReplyTo} is a message at all is decided from its prefix, as {@link
   * #receiveIdTypeOf} decides the other two: {@code om_} is a message. Anything else — a browser
   * conversation's UUID, most obviously, since a conversation that began in the page has no Feishu
   * message behind it — is not a message id, and the card is sent to the chat instead. Sniffing the
   * prefix rather than asking the caller, because the caller would then have to know what a Feishu
   * identifier looks like.
   */
  @Override
  public void send(final Route route, final String inReplyTo, final String text) {
    if (route == null || route.isEmpty()) {
      // A deployment that configured nowhere to send this wanted nowhere, and a caller should not
      // have to know which surface is installed to work that out.
      return;
    }
    final var receiveId = route.chatId();
    final String content;
    try {
      content = messageCard.render(text);
    } catch (Exception e) {
      throw new IllegalStateException("Could not render a notification card", e);
    }

    if (isMessageId(inReplyTo)) {
      reply(inReplyTo, content);
      return;
    }

    final CreateMessageReq request;
    final com.lark.oapi.service.im.v1.model.CreateMessageResp response;
    try {
      request =
          CreateMessageReq.newBuilder()
              .receiveIdType(receiveIdTypeOf(receiveId))
              .createMessageReqBody(
                  CreateMessageReqBody.newBuilder()
                      .receiveId(receiveId)
                      .msgType("interactive")
                      .content(content)
                      .build())
              .build();
      response = feishu.im().v1().message().create(request);
    } catch (Exception e) {
      throw new IllegalStateException("Could not send a notification to " + receiveId, e);
    }
    if (response.getCode() != 0) {
      throw new IllegalStateException(
          "Could not send a notification to " + receiveId + ": " + response.getMsg());
    }
    log.info("Sent a notification to {}", receiveId);
  }

  private void reply(final String messageId, final String content) {
    final com.lark.oapi.service.im.v1.model.ReplyMessageResp response;
    try {
      response =
          feishu
              .im()
              .v1()
              .message()
              .reply(
                  ReplyMessageReq.newBuilder()
                      .messageId(messageId)
                      .replyMessageReqBody(
                          ReplyMessageReqBody.newBuilder()
                              .msgType("interactive")
                              .content(content)
                              .build())
                      .build());
    } catch (Exception e) {
      throw new IllegalStateException("Could not reply a notification onto " + messageId, e);
    }
    if (response.getCode() != 0) {
      throw new IllegalStateException(
          "Could not reply a notification onto " + messageId + ": " + response.getMsg());
    }
    log.info("Replied a notification onto {}", messageId);
  }

  /**
   * Whether this is a Feishu message id, and so something a card can be threaded under. Feishu's
   * own prefix for one; see {@link #receiveIdTypeOf} for why a prefix is what decides here.
   */
  static boolean isMessageId(final String id) {
    return id != null && id.startsWith("om_");
  }

  @Override
  public String surface() {
    return "feishu";
  }

  /**
   * {@inheritDoc}
   *
   * <p>Delegated to {@link FeishuMarkdown}, which holds the reason each character is on the list.
   * Overridden rather than left to the default because this surface has tags that notify people:
   * the default is the honest answer only for an implementation whose dialect does nothing.
   */
  @Override
  public String quoted(final String text) {
    return FeishuMarkdown.escaped(text);
  }

  /**
   * Which kind of id this is, from its prefix.
   *
   * <p>From the id rather than from {@link Route#chatType()}, which is free-form across this
   * codebase and carries {@code group}/{@code p2p} — a description of the conversation, not of the
   * identifier. Feishu's own prefixes are the thing that actually says which: {@code oc_} is a chat
   * and {@code ou_} is a person. Anything else is passed as an open id and left to Feishu to
   * reject, which is a clearer failure than guessing.
   */
  private static String receiveIdTypeOf(final String receiveId) {
    return receiveId.startsWith("oc_") ? "chat_id" : "open_id";
  }
}
