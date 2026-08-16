package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

class ScheduledTaskToolTest {

  private final ScheduledTaskRepo repo = mock(ScheduledTaskRepo.class);
  private final ScheduledTaskService service = mock(ScheduledTaskService.class);
  private final ScheduledTaskTool tool = new ScheduledTaskTool(repo, service);

  private final ToolContext context =
      new ToolContext(
          Map.of(
              ToolContexts.KEY_USER_ID,
              "ou_1",
              ToolContexts.KEY_ROOT_MESSAGE_ID,
              "om_root",
              ToolContexts.KEY_CHAT_ID,
              "oc_1",
              ToolContexts.KEY_CHAT_TYPE,
              "p2p"));

  @BeforeEach
  void setUp() {
    // The backends assign nothing, so what goes in is what comes back out.
    when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  @DisplayName("a cron task is saved with an id, since no backend generates one")
  void cronTaskCarriesAnId() {
    final var result =
        tool.createScheduledTask(
            "summarise the thread", "0 0 9 * * MON", null, null, null, context);

    assertThat(saved().id()).isNotBlank();
    assertThat(result).contains(saved().id());
    verify(service).schedule(any());
  }

  @Test
  @DisplayName("a one-shot task is saved with an id too")
  void oneShotTaskCarriesAnId() {
    final var fireAt = Instant.now().plus(1, ChronoUnit.HOURS);

    tool.createScheduledTask("ping me", null, fireAt.toString(), null, null, context);

    assertThat(saved().id()).isNotBlank();
    verify(service).schedule(any());
  }

  @Test
  @DisplayName("two tasks do not share an id")
  void idsAreDistinct() {
    tool.createScheduledTask("first", "0 0 9 * * MON", null, null, null, context);
    tool.createScheduledTask("second", "0 0 9 * * MON", null, null, null, context);

    final var captor = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(repo, org.mockito.Mockito.times(2)).save(captor.capture());
    assertThat(captor.getAllValues().get(0).id()).isNotEqualTo(captor.getAllValues().get(1).id());
  }

  @Test
  @DisplayName("an invalid cron is refused, not stored as if it were an expression")
  void invalidCronIsRefused() {
    final var result =
        tool.createScheduledTask("summarise", "not a cron", null, null, null, context);

    assertThat(result).startsWith("Error:");
    verify(repo, never()).save(any());
    verify(service, never()).schedule(any());
  }

  @Test
  @DisplayName("a sub-minute cron is raised to the minimum interval rather than refused")
  void subMinuteCronIsRaised() {
    final var result = tool.createScheduledTask("poll", "0 */1 * * * *", null, null, null, context);

    assertThat(saved().cronExpression()).isEqualTo("0 */5 * * * *");
    assertThat(result).contains("raised to the smallest one allowed");
  }

  @Test
  @DisplayName("a task is only background when it was asked to be, on either schedule")
  void backgroundIsCarriedOntoTheTask() {
    tool.createScheduledTask("say nothing unless X", "0 0 9 * * MON", null, null, true, context);
    assertThat(saved().background()).isTrue();

    final var fireAt = Instant.now().plus(1, ChronoUnit.HOURS);
    tool.createScheduledTask("send the report", null, fireAt.toString(), null, true, context);
    assertThat(saved().background()).isTrue();

    tool.createScheduledTask("summarise the thread", "0 0 9 * * MON", null, null, null, context);
    assertThat(saved().background()).isNotEqualTo(true);
  }

  @Test
  @DisplayName("a background task says so, so the model can tell the user what it made")
  void backgroundIsMentionedInTheConfirmation() {
    final var result =
        tool.createScheduledTask(
            "say nothing unless X", "0 0 9 * * MON", null, null, true, context);

    assertThat(result).contains("runs in the background");
  }

  private ScheduledTask saved() {
    final var captor = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(repo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    return captor.getValue();
  }
}
