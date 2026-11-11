package me.kezhenxu94.springagent.integration.websocket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.agent.ScenarioMemos;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.models.ChatSession;
import me.kezhenxu94.springagent.core.dao.models.PendingDeviceCall;
import me.kezhenxu94.springagent.core.dao.repo.ChatReasoningRepo;
import me.kezhenxu94.springagent.core.dao.repo.ChatSessionRepo;
import me.kezhenxu94.springagent.core.dao.repo.PendingDeviceCallRepo;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.dao.repo.UserPreferenceRepo;
import me.kezhenxu94.springagent.core.preferences.UserPreferences;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import me.kezhenxu94.springagent.integration.websocket.run.DeviceTools;
import me.kezhenxu94.springagent.integration.websocket.run.RunJournals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import tools.jackson.databind.json.JsonMapper;

/** What a conversation's state says about device calls waiting on the phone, and what ends them. */
class ChatPendingDeviceCallsTest {

  private static final String ME = "ou_me";
  private static final String CONV = "conv-1";
  private static final DefaultOAuth2User PRINCIPAL =
      new DefaultOAuth2User(List.of(), Map.of("open_id", ME, "name", "Me"), "open_id");

  private final WebProperties properties =
      new WebProperties(
          null, null, null, Locale.ENGLISH, null, null, null, null, false, null, null);
  private final WebMessages messages = new WebMessages(properties);
  private final JsonMapper om = JsonMapper.builder().build();
  private final ChatSessionRepo sessionRepo = mock(ChatSessionRepo.class);
  private final PendingDeviceCallRepo calls = mock(PendingDeviceCallRepo.class);
  private final RunJournals journals = new RunJournals(properties, List.of());
  private final ScenarioMemos memos = new ScenarioMemos(List.of());

  @SuppressWarnings("unchecked")
  private final ChatController controller =
      new ChatController(
          mock(SpringAgent.class),
          mock(ObjectProvider.class),
          mock(ObjectProvider.class),
          null,
          null,
          mock(ObjectProvider.class),
          new ChatSessions(sessionRepo, mock(ChatReasoningRepo.class), null),
          journals,
          mock(PendingQuestionRepo.class),
          calls,
          null,
          null,
          null,
          new DeviceToolDeclarations(
              new DeviceTools(journals, messages, om, properties, calls), messages),
          memos,
          new UserPreferences(mock(UserPreferenceRepo.class), memos),
          messages,
          properties,
          om);

  @BeforeEach
  void mine() {
    when(sessionRepo.findById(CONV))
        .thenReturn(Optional.of(ChatSession.builder().id(CONV).userId(ME).build()));
  }

  private static PendingDeviceCall call(
      final String id, final String userId, final Instant createdAt, final Instant expiresAt) {
    return PendingDeviceCall.builder()
        .id(id)
        .userId(userId)
        .conversationId(CONV)
        .toolName("device_location")
        .input("{\"precise\":true}")
        .status(PendingDeviceCall.Status.PENDING)
        .createdAt(createdAt)
        .expiresAt(expiresAt)
        .build();
  }

  @Test
  @DisplayName("the state lists the caller's unexpired pending device calls, oldest first")
  void stateListsPendingDeviceCalls() {
    final var now = Instant.now();
    final var later = now.plusSeconds(3600);
    when(calls.findByConversationIdAndStatus(CONV, PendingDeviceCall.Status.PENDING))
        .thenReturn(
            List.of(
                call("call-2", ME, now.minusSeconds(10), later),
                call("call-1", ME, now.minusSeconds(20), later),
                call("expired", ME, now.minusSeconds(30), now.minusSeconds(1)),
                call("theirs", "ou_other", now.minusSeconds(40), later)));

    final var state = controller.state(PRINCIPAL, CONV);
    @SuppressWarnings("unchecked")
    final var listed = (List<Map<String, Object>>) state.get("pendingDeviceCalls");
    assertThat(listed).extracting(it -> it.get("callId")).containsExactly("call-1", "call-2");
    assertThat(listed.getFirst())
        .containsEntry("name", "device_location")
        .containsEntry("input", "{\"precise\":true}")
        .containsEntry("createdAt", now.minusSeconds(20));
  }

  @Test
  @DisplayName("a typed message supersedes the device calls still waiting in the conversation")
  void aMessageSupersedesPendingDeviceCalls() {
    when(calls.findByConversationIdAndStatus(CONV, PendingDeviceCall.Status.PENDING))
        .thenReturn(List.of(call("call-1", ME, Instant.now(), null)));
    controller.send(PRINCIPAL, CONV, new ChatController.Send("never mind", null, null));
    verify(calls).updateStatus("call-1", PendingDeviceCall.Status.SUPERSEDED);
  }
}
