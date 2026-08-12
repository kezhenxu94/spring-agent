package me.kezhenxu94.springagent.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springframework.ai.chat.metadata.Usage;

class ScheduledTaskFiringEventTest {

  private final ScheduledTaskFiringEvent event =
      new ScheduledTaskFiringEvent(ScheduledTask.builder().id("task-1").build());

  @Test
  @DisplayName("a firing nobody listens to still yields a usable, empty composition")
  void noListeners() {
    assertThat(event.responseListeners()).isEmpty();
    assertThat(event.toolContext()).isEmpty();
    // Must not throw: the scheduler always passes this handler to the tools provider.
    event.todoEventHandler().handle(new Todos(List.of()));
  }

  @Test
  @DisplayName("todo updates fan out to every registered handler")
  void todoHandlersFanOut() {
    final var received = new ArrayList<String>();
    event.addTodoEventHandler(todos -> received.add("first"));
    event.addTodoEventHandler(todos -> received.add("second"));

    event.todoEventHandler().handle(new Todos(List.of()));

    assertThat(received).containsExactly("first", "second");
  }

  @Test
  @DisplayName("listeners are exposed in registration order")
  void responseListenersPreserveOrder() {
    final var first = new RecordingListener();
    final var second = new RecordingListener();
    event.addResponseListener(first);
    event.addResponseListener(second);

    assertThat(event.responseListeners()).containsExactly(first, second);
  }

  @Test
  @DisplayName("tool context entries contributed by listeners are collected")
  void toolContextIsCollected() {
    event.addToolContext("cardUpdater", "the-updater");

    assertThat(event.toolContext()).containsExactly(Map.entry("cardUpdater", "the-updater"));
  }

  private static final class RecordingListener implements AgentResponseListener {
    @Override
    public void onContent(String contentSoFar) {}

    @Override
    public void onUsage(String model, Usage usage) {}

    @Override
    public void onError(Throwable error) {}
  }
}
