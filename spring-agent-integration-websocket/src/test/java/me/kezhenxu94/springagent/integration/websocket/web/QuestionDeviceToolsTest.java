package me.kezhenxu94.springagent.integration.websocket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.repo.ChatReasoningRepo;
import me.kezhenxu94.springagent.core.dao.repo.ChatSessionRepo;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
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

/**
 * Answering a question starts a run of its own, and the phone that answered brings its tools to it
 * — checked by the same rules as a message, and before the question is spent.
 */
class QuestionDeviceToolsTest {

  private static final String ME = "ou_me";

  private final WebProperties properties =
      new WebProperties(null, null, null, Locale.ENGLISH, null, null, null, null, false, null);
  private final WebMessages messages = new WebMessages(properties);
  private final JsonMapper om = JsonMapper.builder().build();
  private final SpringAgent springAgent = mock(SpringAgent.class);
  private final PendingQuestionRepo questions = mock(PendingQuestionRepo.class);

  @SuppressWarnings("unchecked")
  private final QuestionController controller =
      new QuestionController(
          springAgent,
          questions,
          new ChatSessions(mock(ChatSessionRepo.class), mock(ChatReasoningRepo.class), null),
          null,
          new DeviceToolDeclarations(
              new DeviceTools(new RunJournals(properties), messages, om), messages),
          messages,
          om);

  private static final DefaultOAuth2User PRINCIPAL =
      new DefaultOAuth2User(List.of(), Map.of("open_id", ME, "name", "Me"), "open_id");

  private void pending() {
    when(questions.findById("q-1"))
        .thenReturn(
            Optional.of(
                PendingQuestion.builder()
                    .id("q-1")
                    .userId(ME)
                    .conversationId("conv-1")
                    .status(PendingQuestion.Status.PENDING)
                    .questionsJson(
                        "[{\"question\":\"Which?\",\"header\":\"Pick\",\"options\":"
                            + "[{\"label\":\"A\",\"description\":\"a\"}],\"multiSelect\":false}]")
                    .build()));
  }

  private static QuestionController.Answers answers(final List<DeviceTools.Spec> tools) {
    return new QuestionController.Answers(
        List.of(new WebQuestions.Submitted(0, List.of(0), null)), null, tools);
  }

  @Test
  @DisplayName("the run an answer starts is offered the device tools the answer declared")
  void answersCarryDeviceTools() {
    pending();
    controller.answer(
        PRINCIPAL,
        "q-1",
        answers(
            List.of(
                new DeviceTools.Spec(
                    "device_location", "Where the phone is.", Map.of("type", "object")))));

    final var fired = ArgumentCaptor.forClass(AgentRequest.class);
    verify(springAgent).fire(fired.capture());
    assertThat(fired.getValue().toolCallbacks())
        .extracting(ToolCallback::getToolDefinition)
        .extracting(it -> it.name())
        .containsExactly("device_location");
  }

  @Test
  @DisplayName("a bad declaration is a 400 and leaves the question open to be answered again")
  void aBadDeclarationDoesNotSpendTheQuestion() {
    pending();
    assertThatThrownBy(
            () ->
                controller.answer(
                    PRINCIPAL,
                    "q-1",
                    answers(
                        List.of(new DeviceTools.Spec("location", "x", Map.of("type", "object"))))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
    verify(questions, never()).updateStatus(anyString(), any());
    verify(springAgent, never()).fire(any());
  }
}
