package me.kezhenxu94.springagent.integration.websocket.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.models.PendingDeviceCall;
import me.kezhenxu94.springagent.core.dao.repo.PendingDeviceCallRepo;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.run.ChatMirrors;
import me.kezhenxu94.springagent.integration.websocket.run.DeviceTools;
import me.kezhenxu94.springagent.integration.websocket.run.WebRunListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The result of a device tool call that outlived its run — see {@link DeviceTools} for when a call
 * becomes pending.
 *
 * <p>The run that made the call is over, so this is {@code QuestionController} for a device: a new
 * run in the same conversation, whose message is the result, with the same refusals for the same
 * reason — two phones, or a phone retrying, may post for one call. Unless a run is already going
 * there — the answer to another call the same turn left waiting — in which case the result joins
 * it, as a typed message would, and the response says {@code queued}.
 *
 * <p>That message is exactly
 *
 * <pre>
 * &lt;device-result name="{tool name}" outcome="{answered|error|declined}"&gt;
 * {output}
 * </pre>
 *
 * a first line a native app recognises in the transcript, to draw the turn as the tool's result
 * rather than as something the person typed, and that the model reads as one. Keep it exactly so.
 */
@Slf4j
@RestController
@RequestMapping("/api/device-calls")
@RequiredArgsConstructor
public class DeviceCallController {

  /** What the model is told in place of output when the person turned the request down. */
  static final String DECLINED = "The person declined this request on their phone.";

  private final SpringAgent springAgent;
  private final PendingDeviceCallRepo pendingDeviceCallRepo;
  private final ChatSessions sessions;
  private final ChatMirrors mirrors;
  private final DeviceToolDeclarations deviceToolDeclarations;
  private final WebMessages messages;

  /**
   * @param output what the tool produced, or with {@code error} what went wrong
   * @param declined the person said no; {@code output} is then ignored
   * @param mirror and {@code deviceTools}: as on {@code QuestionController.Answers}, since this too
   *     starts a run on the phone's behalf
   */
  public record Result(
      String output,
      boolean error,
      boolean declined,
      Boolean mirror,
      List<DeviceTools.Spec> deviceTools) {}

  @PostMapping("/{callId}/result")
  public Map<String, Object> result(
      @AuthenticationPrincipal final OAuth2User principal,
      @PathVariable final String callId,
      @RequestBody final Result body) {
    final var user = ChatController.user(principal);
    final var pending =
        pendingDeviceCallRepo
            .findById(callId)
            .filter(it -> user.id().equals(it.userId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (pending.status() != PendingDeviceCall.Status.PENDING) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          messages.get("device-call-" + pending.status().name().toLowerCase()));
    }
    if (pending.expiresAt() != null && pending.expiresAt().isBefore(Instant.now())) {
      pendingDeviceCallRepo.updateStatus(callId, PendingDeviceCall.Status.EXPIRED);
      throw new ResponseStatusException(HttpStatus.CONFLICT, messages.get("device-call-expired"));
    }

    final var requestId = UUID.randomUUID().toString();
    // Before the call is spent, so a declaration refused here leaves it for the app to post again.
    final var onDevice =
        deviceToolDeclarations.callbacks(
            requestId, user.id(), body == null ? null : body.deviceTools());

    // Marked answered before the run is fired, so a second post racing this one loses.
    pendingDeviceCallRepo.updateStatus(callId, PendingDeviceCall.Status.ANSWERED);

    final var text = asMessage(pending.toolName(), body);
    final var session = sessions.ownedBy(pending.conversationId(), user).orElse(null);
    final var builder =
        AgentRequest.builder()
            .requestId(requestId)
            .scenario(BuiltInScenarios.CHAT)
            .userId(user.id())
            .chatId(pending.chatId())
            .chatType(WebRunListener.CHAT_TYPE)
            // The group for the reason QuestionController gives.
            .groupId(session == null ? null : session.groupId())
            .tenantId(user.tenantId())
            .conversationId(pending.conversationId())
            .rootMessageId(pending.rootMessageId())
            .replyMessageId(requestId)
            .toolCallbacks(onDevice)
            .userMessage(spec -> spec.text(text));
    if (body != null && Boolean.TRUE.equals(body.mirror())) {
      final var mirror = mirrors.forRun(session, user, text);
      if (mirror != null) {
        builder.listener(mirror);
      }
    }
    // fireOrQueue rather than fire: a turn that left two calls waiting gets two answers, and the
    // second must join the run the first started rather than run beside it in one conversation.
    final var queued = springAgent.fireOrQueue(builder.build(), () -> text, text);

    if (session != null) {
      sessions.touch(session);
    }
    log.info(
        "Device call {} answered by {}, {} run {}",
        callId,
        user.id(),
        queued ? "queued onto the" : "continuing as",
        requestId);
    return Map.of("requestId", requestId, "queued", queued);
  }

  static String asMessage(final String toolName, final Result body) {
    final String outcome;
    final String output;
    if (body != null && body.declined()) {
      outcome = "declined";
      output = DECLINED;
    } else {
      outcome = body != null && body.error() ? "error" : "answered";
      output = body == null || body.output() == null ? "" : body.output();
    }
    // The name is safe inside the quotes: DeviceTools only lets device_[A-Za-z0-9_] through.
    return "<device-result name=\"" + toolName + "\" outcome=\"" + outcome + "\">\n" + output;
  }
}
