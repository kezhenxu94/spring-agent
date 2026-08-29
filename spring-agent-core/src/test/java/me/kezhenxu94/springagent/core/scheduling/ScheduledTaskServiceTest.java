package me.kezhenxu94.springagent.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** What a firing says to the model, which {@code app.ai.scheduled-task-prompt} decides. */
class ScheduledTaskServiceTest {

  private final SpringAgent springAgent = mock(SpringAgent.class);
  private final ScheduledTaskRepo repo = mock(ScheduledTaskRepo.class);

  private final ScheduledTask task =
      ScheduledTask.builder()
          .id("task-1")
          .userId("ou_1")
          .rootMessageId("om_root")
          .taskText("summarise yesterday's thread")
          .cronExpression("0 0 9 * * MON")
          .status(ScheduledTask.Status.ACTIVE)
          .build();

  @Test
  @DisplayName("the configured template wraps the task's own text")
  void configuredTemplateIsRendered() {
    final var message = fireAndCaptureUserMessage("Time to work:\n{taskText}\nGo.");

    assertThat(message).isEqualTo("Time to work:\nsummarise yesterday's thread\nGo.");
  }

  @Test
  @DisplayName("the default template mentions the task and forbids rescheduling it")
  void defaultTemplateIsRendered() {
    final var message = fireAndCaptureUserMessage(null);

    assertThat(message).contains("summarise yesterday's thread");
    assertThat(message).contains("Do not create, reschedule or cancel a scheduled task");
    assertThat(message).doesNotContain("{taskText}");
  }

  @Test
  @DisplayName("a template that cannot render still lets the task run, on its own text")
  void brokenTemplateFallsBackToTheTaskText() {
    final var message = fireAndCaptureUserMessage("Run {somethingNobodyProvides} now");

    assertThat(message).isEqualTo("summarise yesterday's thread");
  }

  @Test
  @DisplayName("a firing tells the surface whether the task is background, unset meaning it is not")
  void backgroundIsCarriedOntoTheRequest() {
    assertThat(fireAndCaptureRequest(task).background()).isFalse();
    assertThat(fireAndCaptureRequest(task.toBuilder().background(true).build()).background())
        .isTrue();
  }

  /** A mock of its own per firing, so a test may fire twice and still verify a single call. */
  private AgentRequest fireAndCaptureRequest(final ScheduledTask task) {
    final var agent = mock(SpringAgent.class);
    when(agent.accepting()).thenReturn(true);
    new ScheduledTaskService(agent, repo, properties(null), mock(ThreadPoolTaskScheduler.class))
        .fire(task);

    final var captor = ArgumentCaptor.forClass(AgentRequest.class);
    verify(agent).fire(captor.capture());
    return captor.getValue();
  }

  private String fireAndCaptureUserMessage(final String template) {
    when(springAgent.accepting()).thenReturn(true);
    final var service =
        new ScheduledTaskService(
            springAgent, repo, properties(template), mock(ThreadPoolTaskScheduler.class));

    service.fire(task);

    final var captor = ArgumentCaptor.forClass(AgentRequest.class);
    verify(springAgent).fire(captor.capture());
    final var spec = mock(ChatClient.PromptUserSpec.class);
    captor.getValue().userMessage().accept(spec);
    final var text = ArgumentCaptor.forClass(String.class);
    verify(spec).text(text.capture());
    return text.getValue();
  }

  private static SpringAgentProperties properties(final String scheduledTaskPrompt) {
    return new SpringAgentProperties(
        null,
        new SpringAgentProperties.Ai(
            Set.of(), Map.of(), null, null, null, "you are an agent", scheduledTaskPrompt, null),
        null);
  }
}
