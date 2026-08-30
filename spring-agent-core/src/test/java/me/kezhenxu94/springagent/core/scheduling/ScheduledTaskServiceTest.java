package me.kezhenxu94.springagent.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
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
  @DisplayName("the default template mentions the task, and what the task may do to itself")
  void defaultTemplateIsRendered() {
    final var message = fireAndCaptureUserMessage(null);

    assertThat(message).contains("summarise yesterday's thread");
    assertThat(message).contains("Do not create a scheduled task");
    assertThat(message).contains("StopThisScheduledTask");
    assertThat(message).contains("RescheduleThisScheduledTask");
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

  @Test
  @DisplayName("a firing carries the group and tenant the task was created in")
  void scopesAreCarriedOntoTheRequest() {
    final var request =
        fireAndCaptureRequest(task.toBuilder().groupId("oc_group").tenantId("tenant_1").build());

    assertThat(request.groupId()).isEqualTo("oc_group");
    assertThat(request.tenantId()).isEqualTo("tenant_1");
  }

  @Test
  @DisplayName("a task with runs left has one counted, and one without runs left is done instead")
  void maxRunsBoundsTheFirings() {
    final var bounded = task.toBuilder().maxRuns(3).runCount(2).build();
    assertThat(fireAndCaptureRequest(bounded)).isNotNull();
    verify(repo).incrementRunCount("task-1");

    final var spent = task.toBuilder().maxRuns(3).runCount(3).build();
    final var agent = mock(SpringAgent.class);
    when(agent.accepting()).thenReturn(true);
    when(repo.findById("task-1")).thenReturn(Optional.of(spent));
    new ScheduledTaskService(agent, repo, properties(null), mock(ThreadPoolTaskScheduler.class))
        .fire(spent);

    verify(agent, never()).fire(org.mockito.ArgumentMatchers.any());
    verify(repo).updateStatus("task-1", ScheduledTask.Status.COMPLETED);
  }

  @Test
  @DisplayName("a task cancelled since its timer was armed is not fired")
  void aCancelledTaskIsNotFired() {
    final var cancelled = task.toBuilder().status(ScheduledTask.Status.CANCELLED).build();
    final var agent = mock(SpringAgent.class);
    when(agent.accepting()).thenReturn(true);
    when(repo.findById("task-1")).thenReturn(Optional.of(cancelled));

    new ScheduledTaskService(agent, repo, properties(null), mock(ThreadPoolTaskScheduler.class))
        .fire(cancelled);

    verify(agent, never()).fire(org.mockito.ArgumentMatchers.any());
    verify(repo, never()).incrementRunCount(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  @DisplayName("a task stopping itself does not cancel the run that is stopping it")
  void stoppingDoesNotCancelTheFiringRun() {
    final var service =
        new ScheduledTaskService(
            springAgent, repo, properties(null), mock(ThreadPoolTaskScheduler.class));

    service.stopFiringTask("task-1");

    verify(repo).updateStatus("task-1", ScheduledTask.Status.COMPLETED);
    verify(springAgent, never()).cancel("task-1");
  }

  /** A mock of its own per firing, so a test may fire twice and still verify a single call. */
  private AgentRequest fireAndCaptureRequest(final ScheduledTask task) {
    final var agent = mock(SpringAgent.class);
    when(agent.accepting()).thenReturn(true);
    // A firing reads the task back before it runs, so the repository has to hold it.
    when(repo.findById(task.id())).thenReturn(Optional.of(task));
    new ScheduledTaskService(agent, repo, properties(null), mock(ThreadPoolTaskScheduler.class))
        .fire(task);

    final var captor = ArgumentCaptor.forClass(AgentRequest.class);
    verify(agent).fire(captor.capture());
    return captor.getValue();
  }

  private String fireAndCaptureUserMessage(final String template) {
    when(springAgent.accepting()).thenReturn(true);
    when(repo.findById(task.id())).thenReturn(Optional.of(task));
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
