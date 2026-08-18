package me.kezhenxu94.springagent.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler;

class AgentRunRegistryTest {

  private final AgentRunRegistry registry =
      new AgentRunRegistry(
          AgentRequest.builder()
              .requestId("request-1")
              .scenario(BuiltInScenarios.SCHEDULED_TASK)
              .userMessage(user -> user.text("hi"))
              .build());

  @Test
  @DisplayName("a run nobody listens to still yields a usable, empty contribution")
  void noListeners() {
    assertThat(registry.responseListeners()).isEmpty();
    assertThat(registry.todoEventHandlers()).isEmpty();
    assertThat(registry.toolContext()).isEmpty();
  }

  @Test
  @DisplayName("todo handlers are exposed in registration order")
  void todoHandlersPreserveOrder() {
    final var received = new ArrayList<String>();
    registry.addTodoEventHandler(todos -> received.add("first"));
    registry.addTodoEventHandler(todos -> received.add("second"));

    registry.todoEventHandlers().forEach(handler -> handler.handle(null));

    assertThat(received).containsExactly("first", "second");
  }

  @Test
  @DisplayName("listeners are exposed in registration order")
  void responseListenersPreserveOrder() {
    final var first = new RecordingListener();
    final var second = new RecordingListener();
    registry.addResponseListener(first);
    registry.addResponseListener(second);

    assertThat(registry.responseListeners()).containsExactly(first, second);
  }

  @Test
  @DisplayName("tool context entries contributed by listeners are collected")
  void toolContextIsCollected() {
    registry.addToolContext("cardUpdater", "the-updater");

    assertThat(registry.toolContext()).containsExactly(Map.entry("cardUpdater", "the-updater"));
  }

  @Test
  @DisplayName("the request is exposed so a listener can decide whether the run concerns it")
  void requestIsExposed() {
    assertThat(registry.request().scenario()).isEqualTo(BuiltInScenarios.SCHEDULED_TASK);
    assertThat(registry.request().requestId()).isEqualTo("request-1");
  }

  @Test
  @DisplayName("a question handler is kept for an attended run and dropped for a background one")
  void backgroundRunCannotBeAskedQuestions() {
    final QuestionHandler handler = questions -> Map.of();

    registry.addQuestionHandler(handler);
    assertThat(registry.questionHandlers()).containsExactly(handler);

    final var background =
        new AgentRunRegistry(
            AgentRequest.builder()
                .requestId("request-2")
                .scenario(BuiltInScenarios.SCHEDULED_TASK)
                .background(true)
                .userMessage(user -> user.text("hi"))
                .build());
    background.addQuestionHandler(handler);

    assertThat(background.questionHandlers()).isEmpty();
  }

  private static final class RecordingListener implements AgentResponseListener {}
}
