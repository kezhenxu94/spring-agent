package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * What a firing may do to the task it is a firing of. The property under test throughout is that
 * neither tool can ever produce a second task.
 */
class FiringScheduledTaskToolTest {

  private final ScheduledTaskRepo repo = mock(ScheduledTaskRepo.class);
  private final ScheduledTaskService service = mock(ScheduledTaskService.class);
  private final FiringScheduledTaskTool tool =
      new FiringScheduledTaskTool(repo, service, messages());

  /** A firing carries the task's own id as its request id; that is how the tools find the task. */
  private final ToolContext context =
      new ToolContext(Map.of(ToolContexts.KEY_REQUEST_ID, "task-1"));

  private final Instant later = Instant.now().plus(30, ChronoUnit.MINUTES);

  @BeforeEach
  void setUp() {
    when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  @DisplayName("a run that is not a firing has no task to act on, and both tools say so")
  void aRunThatIsNotAFiringIsRefused() {
    when(repo.findById("task-1")).thenReturn(Optional.empty());

    assertThat(tool.stopThisScheduledTask("done", context)).contains("not a scheduled task firing");
    assertThat(tool.rescheduleThisScheduledTask(later.toString(), null, context))
        .contains("not a scheduled task firing");
    verify(repo, never()).save(any());
    verify(service, never()).stopFiringTask(anyString());
  }

  @Test
  @DisplayName("a task already cancelled is left alone, since there is nothing left to change")
  void aTaskThatIsNoLongerActiveIsRefused() {
    when(repo.findById("task-1"))
        .thenReturn(Optional.of(oneOff().status(ScheduledTask.Status.CANCELLED).build()));

    assertThat(tool.stopThisScheduledTask("done", context)).contains("not a scheduled task firing");
    verify(service, never()).stopFiringTask(anyString());
  }

  @Test
  @DisplayName("stopping ends this task and nothing else")
  void stoppingEndsThisTask() {
    when(repo.findById("task-1")).thenReturn(Optional.of(oneOff().build()));

    final var result = tool.stopThisScheduledTask("the build went green", context);

    verify(service).stopFiringTask("task-1");
    assertThat(result).contains("task-1").contains("the build went green");
  }

  @Test
  @DisplayName("a follow-up moves the one task, and creates none")
  void aFollowUpMovesTheSameTask() {
    when(repo.findById("task-1")).thenReturn(Optional.of(oneOff().build()));

    final var result =
        tool.rescheduleThisScheduledTask(later.toString(), "still not merged", context);

    final var saved = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(repo).save(saved.capture());
    assertThat(saved.getValue().id()).isEqualTo("task-1");
    assertThat(saved.getValue().scheduledAt()).isEqualTo(later);
    assertThat(saved.getValue().taskText()).isEqualTo("still not merged");
    verify(service).rearmFiringTask(any());
    assertThat(result).contains("task-1");
  }

  @Test
  @DisplayName("a follow-up that says nothing new fires with the text the task already had")
  void aFollowUpKeepsTheTextWhenGivenNone() {
    when(repo.findById("task-1")).thenReturn(Optional.of(oneOff().build()));

    tool.rescheduleThisScheduledTask(later.toString(), null, context);

    final var saved = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(repo).save(saved.capture());
    assertThat(saved.getValue().taskText()).isEqualTo("check the pull request");
  }

  @Nested
  @DisplayName("a follow-up is refused when it would not be one")
  class RefusedFollowUps {

    @Test
    @DisplayName("a task that already repeats has a next firing of its own")
    void aRecurringTaskIsRefused() {
      when(repo.findById("task-1"))
          .thenReturn(
              Optional.of(oneOff().scheduledAt(null).cronExpression("0 0 9 * * MON").build()));

      assertThat(tool.rescheduleThisScheduledTask(later.toString(), null, context))
          .contains("StopThisScheduledTask");
      verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("a time in the past, or one that is not a time at all")
    void aBadTimeIsRefused() {
      when(repo.findById("task-1")).thenReturn(Optional.of(oneOff().build()));

      assertThat(tool.rescheduleThisScheduledTask("tomorrow morning", null, context))
          .contains("ISO-8601");
      assertThat(
              tool.rescheduleThisScheduledTask(
                  Instant.now().minus(1, ChronoUnit.MINUTES).toString(), null, context))
          .contains("future");
      verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("a time past the day the task stops being wanted at all")
    void aTimeBeyondExpiryIsRefused() {
      final var expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);
      when(repo.findById("task-1")).thenReturn(Optional.of(oneOff().expiresAt(expiresAt).build()));

      assertThat(tool.rescheduleThisScheduledTask(later.toString(), null, context))
          .contains(expiresAt.toString());
      verify(repo, never()).save(any());
    }
  }

  private ScheduledTask.ScheduledTaskBuilder oneOff() {
    return ScheduledTask.builder()
        .id("task-1")
        .userId("ou_1")
        .rootMessageId("om_root")
        .taskText("check the pull request")
        .scheduledAt(Instant.now().plus(1, ChronoUnit.MINUTES))
        .status(ScheduledTask.Status.ACTIVE);
  }

  private static CoreMessages messages() {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    return new CoreMessages(
        source, new SpringAgentProperties(null, null, Locale.ENGLISH, null, null));
  }
}
