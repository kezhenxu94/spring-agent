package me.kezhenxu94.springagent.integration.slack.usermodels;

import com.slack.api.bolt.context.builtin.ActionContext;
import com.slack.api.bolt.context.builtin.SlashCommandContext;
import com.slack.api.bolt.context.builtin.ViewSubmissionContext;
import com.slack.api.bolt.request.builtin.BlockActionRequest;
import com.slack.api.bolt.request.builtin.SlashCommandRequest;
import com.slack.api.bolt.request.builtin.ViewSubmissionRequest;
import com.slack.api.bolt.response.Response;
import com.slack.api.methods.MethodsClient;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import me.kezhenxu94.springagent.integration.slack.handler.SlackQuestionForm;
import org.springframework.core.task.TaskExecutor;

/**
 * The {@code /config} modal: opening it, and acting on what comes back.
 *
 * <p>The Feishu handler's counterpart. This is the escape hatch, and everything about it is shaped
 * by that: no path here reaches an LLM, so it answers whatever state the user's chosen endpoint is
 * in — which is the point, since a bad endpoint would otherwise break the very run needed to undo
 * it.
 *
 * <p>Switching is applied inside the submission, because it costs nothing and it is the half
 * somebody locked out needs. Adding is handed to a background thread and its result sent as a
 * message, because it probes an endpoint that may take seconds to answer while Slack expects the
 * submission acknowledged within three.
 */
@Slf4j
@RequiredArgsConstructor
public class SlackConfigHandler {

  private final MethodsClient slack;
  private final UserModelRegistry registry;
  private final UserModelProbe probe;
  private final BuiltinModels builtins;
  private final SlackConfigForm form;
  private final SlackMessages messages;

  /** See {@code SlackQuestionAnswerHandler} for why this pool and not the scheduler's. */
  private final TaskExecutor taskExecutor;

  /**
   * Opens the modal, in answer to {@code /config}.
   *
   * <p>Everything happens on the request thread rather than on the executor, which is unusual here
   * and is forced: a {@code trigger_id} is valid for about three seconds and can be spent once, so
   * handing the work to another thread is how the modal fails to open. Both slow steps are bounded
   * to suit — the model listing is cached and gives up after five seconds.
   */
  public Response handle(final SlashCommandRequest request, final SlashCommandContext ctx) {
    final var userId = ctx.getRequestUserId();
    if (userId == null) {
      return ctx.ack();
    }
    try {
      final var configured = registry.list(userId);
      final var active = registry.active(userId).map(UserModelConfig::name).orElse(null);
      // Best-effort, and never on the critical path: an endpoint that will not list its models
      // leaves this empty and the modal offers the single built-in entry instead.
      final var view = form.view(configured, active, builtins.list(), builtins.defaultModel());
      final var response = slack.viewsOpen(r -> r.triggerId(ctx.getTriggerId()).view(view));
      if (!response.isOk()) {
        log.error("Could not open the model settings for {}: {}", userId, response.getError());
        return ctx.ack(messages.get("config-failed"));
      }
      return ctx.ack();
    } catch (Exception e) {
      log.error("Could not open the model settings for {}", userId, e);
      return ctx.ack(messages.get("config-failed"));
    }
  }

  /**
   * Acts on the modal being submitted.
   *
   * <p>What the user is told comes back as a direct message rather than in the modal: closing it is
   * the acknowledgement Slack expects, and an error shown in a modal that stays open would leave
   * the token typed into a form still on screen.
   */
  public Response handle(final ViewSubmissionRequest request, final ViewSubmissionContext ctx) {
    final var payload = request.getPayload();
    final var userId = payload.getUser() == null ? null : payload.getUser().getId();
    if (userId == null) {
      return ctx.ack();
    }
    say(userId, apply(userId, form.read(state(payload))));
    return ctx.ack();
  }

  /**
   * Acts on the in-message form being submitted, which is the fallback where the slash command was
   * never declared on the Slack app.
   *
   * <p>The form is taken off the timeline first and unconditionally: the API token was typed into a
   * channel message in the clear, Slack having no masked input, and a message holding one should
   * not sit in the channel history while the endpoint is being probed.
   */
  public Response handle(final BlockActionRequest request, final ActionContext ctx)
      throws IOException {
    final var payload = request.getPayload();
    final var userId = payload.getUser() == null ? null : payload.getUser().getId();
    if (userId == null) {
      return ctx.ack();
    }
    final var submission = form.read(messageState(payload));
    try {
      ctx.respond(r -> r.deleteOriginal(true).text(""));
    } catch (Exception e) {
      // Logged rather than raised: the change below is worth applying either way, and refusing the
      // press would leave the user unsure what happened as well as looking at the token.
      log.warn("Could not remove the submitted model settings form", e);
    }
    say(userId, apply(userId, submission));
    return ctx.ack();
  }

  /**
   * Applies a submission and returns what to tell the user.
   *
   * <p>Shared by the modal and the in-message form so the two cannot drift into meaning different
   * things — the only difference between them is how the form got on screen.
   */
  private String apply(final String userId, final SlackConfigForm.Submission submission) {
    if (!submission.adding()) {
      return switchTo(userId, submission.active());
    }
    // A model on its own, with no endpoint and no token, is one of the application's own named
    // directly — the way to reach a model the dropdown was too small to list. Nothing to probe: it
    // is the endpoint this application is already talking to.
    if (submission.builtinByName()) {
      registry.activateBuiltin(userId, submission.model());
      return messages.get("config-switched-builtin", submission.model());
    }
    if (!submission.complete()) {
      return messages.get("config-incomplete");
    }
    // Off this thread: the probe can take seconds and both callers are on a clock.
    taskExecutor.execute(() -> add(userId, userId, submission));
    return messages.get("config-testing", submission.name());
  }

  /**
   * Posts the form as a message, in answer to a plain {@code " /config"}.
   *
   * <p>The way in when the slash command was never created on the Slack app: Slack sends a message
   * beginning with a space verbatim instead of looking for a command, so this needs no setup at
   * all. Poorer than the modal — see {@code SlackConfigForm#messageBlocks} — and offered anyway,
   * because a deployment where the command was forgotten is exactly the one whose users cannot ask
   * an administrator to fix their model for them.
   */
  public void open(final String channelId, final String userId) {
    taskExecutor.execute(
        () -> {
          try {
            final var configured = registry.list(userId);
            final var active = registry.active(userId).map(UserModelConfig::name).orElse(null);
            final var blocks =
                form.messageBlocks(configured, active, builtins.list(), builtins.defaultModel());
            final var response =
                slack.chatPostMessage(
                    r -> r.channel(channelId).blocks(blocks).text(messages.get("config-title")));
            if (!response.isOk()) {
              log.error(
                  "Could not post the model settings to {}: {}", channelId, response.getError());
            }
          } catch (Exception e) {
            log.error("Could not open the model settings for {} in {}", userId, channelId, e);
          }
        });
  }

  /** Applies the dropdown, and says what it did. */
  private String switchTo(final String userId, final String active) {
    if (active == null) {
      return messages.get("config-nothing");
    }
    if (SlackConfigForm.DEFAULT_OPTION.equals(active)) {
      registry.useDefault(userId);
      return messages.get("config-switched-default");
    }
    if (active.startsWith(SlackConfigForm.BUILTIN_OPTION)) {
      final var model = active.substring(SlackConfigForm.BUILTIN_OPTION.length());
      registry.activateBuiltin(userId, model);
      return messages.get("config-switched-builtin", model);
    }
    if (!registry.activate(userId, active)) {
      // The form offered it, so it existed when the form was drawn; it has been deleted since.
      // Nothing to correct, and the built-in model is where the user ends up either way.
      return messages.get("config-switched-default");
    }
    return messages.get("config-switched", active);
  }

  /**
   * Tests an endpoint and stores it, then puts the user on it.
   *
   * <p>Switching only after the probe has passed is the point: a form that saved and activated
   * without checking would be a way to lock yourself out through the very form that exists to stop
   * that happening.
   */
  private void add(
      final String channelId, final String userId, final SlackConfigForm.Submission submission) {
    if (registry.full(userId, submission.name())) {
      say(channelId, messages.get("config-too-many", registry.maxPerUser()));
      return;
    }
    if (!UserModelRegistry.validName(submission.name())) {
      say(channelId, messages.get("config-bad-name", submission.name()));
      return;
    }
    final var failure = probe.check(submission.baseUrl(), submission.model(), submission.token());
    if (failure != null) {
      say(channelId, messages.get("config-add-failed", submission.name(), failure));
      return;
    }
    registry.save(
        userId, submission.name(), submission.baseUrl(), submission.model(), submission.token());
    registry.activate(userId, submission.name());
    say(channelId, messages.get("config-added", submission.name()));
  }

  /**
   * Tells the user what happened, as a direct message.
   *
   * <p>The user id is a usable channel for {@code chat.postMessage}, which is what makes this work
   * from a modal: a modal knows who submitted it but not where they were when they opened it.
   */
  private void say(final String userId, final String text) {
    if (userId == null) {
      return;
    }
    try {
      slack.chatPostMessage(r -> r.channel(userId).text(text));
    } catch (Exception e) {
      log.error("Could not send the model settings result to {}", userId, e);
    }
  }

  /** The same, off a block-action payload, where the state sits at the top level. */
  private Map<String, Map<String, SlackQuestionForm.ViewValue>> messageState(
      final com.slack.api.app_backend.interactive_components.payload.BlockActionPayload payload) {
    if (payload.getState() == null || payload.getState().getValues() == null) {
      return Map.of();
    }
    return read(payload.getState().getValues());
  }

  /**
   * Slack's {@code state.values}, narrowed to what this reads. Shares {@code
   * SlackQuestionForm.ViewValue} rather than declaring a second copy of the same two fields.
   */
  private Map<String, Map<String, SlackQuestionForm.ViewValue>> state(
      final com.slack.api.app_backend.views.payload.ViewSubmissionPayload payload) {
    final var view = payload.getView();
    if (view == null || view.getState() == null || view.getState().getValues() == null) {
      return Map.of();
    }
    return read(view.getState().getValues());
  }

  private static Map<String, Map<String, SlackQuestionForm.ViewValue>> read(
      final Map<String, Map<String, com.slack.api.model.view.ViewState.Value>> values) {
    final var out = new LinkedHashMap<String, Map<String, SlackQuestionForm.ViewValue>>();
    values.forEach(
        (blockId, actions) -> {
          final var narrowed = new LinkedHashMap<String, SlackQuestionForm.ViewValue>();
          actions.forEach(
              (actionId, value) -> {
                final var selected =
                    value.getSelectedOption() == null
                        ? null
                        : new SlackQuestionForm.ViewValue.SelectedOption(
                            value.getSelectedOption().getValue());
                narrowed.put(actionId, new SlackQuestionForm.ViewValue(value.getValue(), selected));
              });
          out.put(blockId, narrowed);
        });
    return out;
  }
}
