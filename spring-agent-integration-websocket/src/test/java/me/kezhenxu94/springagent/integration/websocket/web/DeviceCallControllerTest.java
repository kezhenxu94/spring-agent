package me.kezhenxu94.springagent.integration.websocket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.models.PendingDeviceCall;
import me.kezhenxu94.springagent.core.dao.repo.ChatReasoningRepo;
import me.kezhenxu94.springagent.core.dao.repo.ChatSessionRepo;
import me.kezhenxu94.springagent.core.dao.repo.PendingDeviceCallRepo;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import me.kezhenxu94.springagent.integration.websocket.run.DeviceTools;
import me.kezhenxu94.springagent.integration.websocket.run.RunJournals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** A device's result for a call that outlived its run: refusals, and the run it starts. */
class DeviceCallControllerTest {

  private static final String ME = "ou_me";

  private final WebProperties properties =
      new WebProperties(
          null, null, null, Locale.ENGLISH, null, null, null, null, false, null, null);
  private final WebMessages messages = new WebMessages(properties);
  private final SpringAgent springAgent = mock(SpringAgent.class);
  private final PendingDeviceCallRepo calls = mock(PendingDeviceCallRepo.class);

  private final DeviceCallController controller =
      new DeviceCallController(
          springAgent,
          calls,
          new ChatSessions(mock(ChatSessionRepo.class), mock(ChatReasoningRepo.class), null),
          null,
          new DeviceToolDeclarations(
              new DeviceTools(
                  new RunJournals(properties, List.of()),
                  messages,
                  JsonMapper.builder().build(),
                  properties,
                  calls),
              messages),
          messages);

  private static DefaultOAuth2User principal(final String id) {
    return new DefaultOAuth2User(List.of(), Map.of("open_id", id, "name", id), "open_id");
  }

  private void pending(final PendingDeviceCall.Status status, final Instant expiresAt) {
    when(calls.findById("call-1"))
        .thenReturn(
            Optional.of(
                PendingDeviceCall.builder()
                    .id("call-1")
                    .userId(ME)
                    .chatId("conv-1")
                    .conversationId("conv-1")
                    .rootMessageId("conv-1")
                    .toolName("device_location")
                    .input("{}")
                    .status(status)
                    .expiresAt(expiresAt)
                    .build()));
  }

  private static DeviceCallController.Result result(final List<DeviceTools.Spec> tools) {
    return new DeviceCallController.Result("{\"lat\":1}", false, false, null, tools);
  }

  @Test
  @DisplayName("a result is spent, then continues the conversation as a run with the phone's tools")
  void aResultStartsARun() {
    pending(PendingDeviceCall.Status.PENDING, Instant.now().plusSeconds(60));
    final var response =
        controller.result(
            principal(ME),
            "call-1",
            result(
                List.of(
                    new DeviceTools.Spec(
                        "device_camera", "Takes a photo.", Map.of("type", "object")))));

    final var fired = ArgumentCaptor.forClass(AgentRequest.class);
    final var order = inOrder(calls, springAgent);
    order.verify(calls).updateStatus("call-1", PendingDeviceCall.Status.ANSWERED);
    order.verify(springAgent).fire(fired.capture());
    final var request = fired.getValue();
    assertThat(response).containsEntry("requestId", request.requestId());
    assertThat(request.userId()).isEqualTo(ME);
    assertThat(request.conversationId()).isEqualTo("conv-1");
    assertThat(request.chatId()).isEqualTo("conv-1");
    assertThat(request.rootMessageId()).isEqualTo("conv-1");
    assertThat(request.toolCallbacks())
        .extracting(ToolCallback::getToolDefinition)
        .extracting(it -> it.name())
        .containsExactly("device_camera");
  }

  @Test
  @DisplayName("the message is the documented first line, then the output")
  void theMessageFormat() {
    assertThat(
            DeviceCallController.asMessage(
                "device_location",
                new DeviceCallController.Result("{\"lat\":1}", false, false, null, null)))
        .isEqualTo("<device-result name=\"device_location\" outcome=\"answered\">\n{\"lat\":1}");
    assertThat(
            DeviceCallController.asMessage(
                "device_location",
                new DeviceCallController.Result("no permission", true, false, null, null)))
        .isEqualTo("<device-result name=\"device_location\" outcome=\"error\">\nno permission");
    assertThat(
            DeviceCallController.asMessage(
                "device_location",
                new DeviceCallController.Result("ignored", false, true, null, null)))
        .isEqualTo(
            "<device-result name=\"device_location\" outcome=\"declined\">\n"
                + "The person declined this request on their phone.");
  }

  @Test
  @DisplayName("somebody else's call, or none, is a 404")
  void notTheCallersIsNotFound() {
    pending(PendingDeviceCall.Status.PENDING, null);
    assertThatThrownBy(() -> controller.result(principal("ou_other"), "call-1", result(null)))
        .hasMessageContaining("404");
    assertThatThrownBy(() -> controller.result(principal(ME), "call-2", result(null)))
        .hasMessageContaining("404");
    verify(springAgent, never()).fire(any());
  }

  @Test
  @DisplayName("an answered, superseded or expired call is a 409 and starts nothing")
  void spentIsAConflict() {
    for (final var status :
        List.of(PendingDeviceCall.Status.ANSWERED, PendingDeviceCall.Status.SUPERSEDED)) {
      pending(status, null);
      assertThatThrownBy(() -> controller.result(principal(ME), "call-1", result(null)))
          .hasMessageContaining("409");
    }
    pending(PendingDeviceCall.Status.PENDING, Instant.now().minusSeconds(1));
    assertThatThrownBy(() -> controller.result(principal(ME), "call-1", result(null)))
        .hasMessageContaining("409")
        .hasMessageContaining("expired");
    verify(calls).updateStatus("call-1", PendingDeviceCall.Status.EXPIRED);
    verify(springAgent, never()).fire(any());
  }

  @Test
  @DisplayName("a bad declaration is a 400 and leaves the call open")
  void aBadDeclarationDoesNotSpendTheCall() {
    pending(PendingDeviceCall.Status.PENDING, null);
    assertThatThrownBy(
            () ->
                controller.result(
                    principal(ME),
                    "call-1",
                    result(
                        List.of(new DeviceTools.Spec("location", "x", Map.of("type", "object"))))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
    verify(calls, never()).updateStatus(anyString(), any());
    verify(springAgent, never()).fire(any());
  }
}
