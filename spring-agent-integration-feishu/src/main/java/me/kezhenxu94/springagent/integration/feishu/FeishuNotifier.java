package me.kezhenxu94.springagent.integration.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
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
 * agent says even though the agent did not write it. What it carries is text this codebase wrote
 * about its own workings; nothing a model produced and nothing from an event payload reaches here.
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
