package me.kezhenxu94.springagent.integration.feishu.handler;

import com.google.common.base.Strings;
import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskFiringEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives scheduled task runs a Feishu card to stream into: creates one, replies it onto the message
 * the task was created from, then attaches a {@link FeishuCardUpdater} to the firing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuScheduledTaskListener {

  final Client feishu;
  final JsonMapper om;
  final SpringAgentProperties appConfiguration;

  @Value("classpath:/feishu/reply-card.json")
  final Resource feishuReplyCard;

  @EventListener
  public void onScheduledTaskFiring(final ScheduledTaskFiringEvent event) {
    final var task = event.getTask();
    // Tasks not created through Feishu have nothing to reply to.
    if (Strings.isNullOrEmpty(task.getRootMessageId())) {
      return;
    }
    try {
      final var cardId = createCard(task.getId());
      if (cardId == null || !replyCard(task.getId(), task.getRootMessageId(), cardId)) {
        return;
      }
      final var cardUpdater =
          new FeishuCardUpdater(feishu, om, cardId, appConfiguration.ai().modelPricing());
      event.addResponseListener(cardUpdater);
      event.addTodoEventHandler(cardUpdater);
      event.addToolContext(FeishuCardUpdater.TOOL_CONTEXT_KEY.key(), cardUpdater);
    } catch (Exception e) {
      // Deliberately swallowed: a task that cannot be reported on should still run.
      log.error("Failed to attach a Feishu card to scheduled task {}", task.getId(), e);
    }
  }

  private String createCard(final String taskId) throws Exception {
    final var cardJson = feishuReplyCard.getContentAsString(StandardCharsets.UTF_8);
    final var response =
        feishu
            .cardkit()
            .v1()
            .card()
            .create(
                CreateCardReq.newBuilder()
                    .createCardReqBody(
                        CreateCardReqBody.newBuilder().type("card_json").data(cardJson).build())
                    .build());
    if (response.getCode() != 0) {
      log.error(
          "Failed to create card for scheduled task {}: {}",
          taskId,
          om.writeValueAsString(response));
      return null;
    }
    return response.getData().getCardId();
  }

  private boolean replyCard(final String taskId, final String rootMessageId, final String cardId)
      throws Exception {
    final var response =
        feishu
            .im()
            .v1()
            .message()
            .reply(
                ReplyMessageReq.newBuilder()
                    .messageId(rootMessageId)
                    .replyMessageReqBody(
                        ReplyMessageReqBody.newBuilder()
                            .msgType("interactive")
                            .content(
                                String.format(
                                    """
                                    {
                                      "type": "card",
                                      "data": {
                                        "card_id": "%s"
                                      }
                                    }
                                    """,
                                    cardId))
                            .build())
                    .build());
    if (response.getCode() != 0) {
      log.error(
          "Failed to send card for scheduled task {}: {}", taskId, om.writeValueAsString(response));
      return false;
    }
    return true;
  }
}
