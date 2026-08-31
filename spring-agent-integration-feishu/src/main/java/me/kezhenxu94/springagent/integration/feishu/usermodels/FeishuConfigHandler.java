package me.kezhenxu94.springagent.integration.feishu.usermodels;

import com.lark.oapi.Client;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.handler.FeishuToasts;
import org.springframework.core.task.TaskExecutor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code /config} card: opening it, and acting on what comes back.
 *
 * <p>This is the escape hatch, and everything about it is shaped by that. A user who saves an
 * endpoint with a wrong token would, without this, have to ask the agent to undo it — through the
 * very model that no longer answers. So no path here reaches an LLM: opening the card is a
 * repository read, switching is a repository write, and only adding a model makes a network call,
 * to the endpoint being added rather than to the one in use.
 *
 * <p>The two halves of a submission are independent. Switching is applied immediately, because it
 * is the half somebody locked out needs and it costs nothing. Adding is handed to a background
 * thread, because it probes an endpoint that may take seconds to answer or never answer at all, and
 * a card callback has to be acknowledged inside Feishu's budget of three.
 */
@Slf4j
@RequiredArgsConstructor
public class FeishuConfigHandler {

  private final Client feishu;
  private final UserModelRegistry registry;
  private final UserModelProbe probe;
  private final BuiltinModels builtins;
  private final FeishuConfigForm form;
  private final JsonMapper objectMapper;
  private final FeishuMessages messages;

  /** See {@code FeishuQuestionAnswerHandler} for why this pool and not the scheduler's. */
  private final TaskExecutor taskExecutor;

  /**
   * Sends the card to {@code chatId}. Called from the event thread when a message reading {@code
   * /config} arrives, and returns as soon as the work is handed off.
   */
  public void open(final String chatId, final String userId) {
    taskExecutor.execute(
        () -> {
          try {
            final var configured = registry.list(userId);
            final var active = registry.active(userId).map(UserModelConfig::name).orElse(null);
            // Best-effort, and never on the critical path: an endpoint that will not list its
            // models leaves this empty and the card offers the single built-in entry instead.
            send(chatId, form.card(configured, active, builtins.list(), builtins.defaultModel()));
          } catch (Exception e) {
            log.error("Could not open the model settings for {} in {}", userId, chatId, e);
            sendText(chatId, messages.get("config-failed"));
          }
        });
  }

  /** Acts on a press of the card's save button. */
  public P2CardActionTriggerResponse handle(final P2CardActionTrigger event) {
    final var context = event.getEvent().getContext();
    final var chatId = context.getOpenChatId();
    final var userId = event.getEvent().getOperator().getOpenId();
    final var submission = form.read(event.getEvent().getAction().getFormValue());

    // Adding first, so that a press doing both ends up on the endpoint it just added rather than
    // on whatever the dropdown happened to be showing.
    if (submission.adding()) {
      // A model on its own, with no endpoint and no token, is one of the application's own named
      // directly — the way to reach a model the dropdown was too small to list. Nothing to probe:
      // it is the endpoint this application is already talking to.
      if (submission.builtinByName()) {
        registry.activateBuiltin(userId, submission.model());
        return FeishuToasts.toast(
            "success", messages.get("config-switched-builtin", submission.model()));
      }
      if (!submission.complete()) {
        return FeishuToasts.toast("warning", messages.get("config-incomplete"));
      }
      taskExecutor.execute(() -> add(chatId, userId, submission));
      return FeishuToasts.toast("info", messages.get("config-testing", submission.name()));
    }

    return FeishuToasts.toast("success", switchTo(userId, submission.active()));
  }

  /** Applies the dropdown, and says what it did. */
  private String switchTo(final String userId, final String active) {
    if (active == null) {
      return messages.get("config-nothing");
    }
    if (FeishuConfigForm.DEFAULT_OPTION.equals(active)) {
      registry.useDefault(userId);
      return messages.get("config-switched-default");
    }
    if (active.startsWith(FeishuConfigForm.BUILTIN_OPTION)) {
      // One of the application's own models, so nothing of the user's is needed to reach it — no
      // endpoint, no token, only the name to ask for.
      final var model = active.substring(FeishuConfigForm.BUILTIN_OPTION.length());
      registry.activateBuiltin(userId, model);
      return messages.get("config-switched-builtin", model);
    }
    if (!registry.activate(userId, active)) {
      // The card offered it, so it existed when the card was drawn; it has been deleted since.
      // Nothing to correct, and the built-in model is where the user ends up either way.
      return messages.get("config-switched-default");
    }
    return messages.get("config-switched", active);
  }

  /**
   * Tests an endpoint and stores it, then puts the user on it.
   *
   * <p>Switching only after the probe has passed is the point: a form that saved and activated
   * without checking would be a way to lock yourself out through the very card that exists to stop
   * that happening.
   */
  private void add(
      final String chatId, final String userId, final FeishuConfigForm.Submission submission) {
    if (registry.full(userId, submission.name())) {
      sendText(chatId, messages.get("config-too-many", registry.maxPerUser()));
      return;
    }
    final var failure = probe.check(submission.baseUrl(), submission.model(), submission.token());
    if (failure != null) {
      sendText(chatId, messages.get("config-add-failed", submission.name(), failure));
      return;
    }
    registry.save(
        userId, submission.name(), submission.baseUrl(), submission.model(), submission.token());
    registry.activate(userId, submission.name());
    sendText(chatId, messages.get("config-added", submission.name()));
  }

  /** A plain message, for what the card cannot say because it has already been answered. */
  private void sendText(final String chatId, final String text) {
    send(chatId, objectMapper.createObjectNode().put("text", text).toString(), "text");
  }

  private void send(final String chatId, final String card) {
    send(chatId, card, "interactive");
  }

  private void send(final String chatId, final String content, final String type) {
    try {
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
                              .msgType(type)
                              .content(content)
                              .build())
                      .build());
      if (response.getCode() != 0) {
        log.error("Could not send the model settings to {}: {}", chatId, response.getMsg());
      }
    } catch (Exception e) {
      log.error("Could not send the model settings to {}", chatId, e);
    }
  }
}
