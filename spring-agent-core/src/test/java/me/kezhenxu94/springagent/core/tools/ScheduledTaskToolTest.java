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
import java.util.Optional;
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
              "p2p",
              ToolContexts.KEY_GROUP_ID,
              "oc_group",
              ToolContexts.KEY_TENANT_ID,
              "tenant_1"));

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
            "summarise the thread", "0 0 9 * * MON", null, null, null, null, context);

    assertThat(saved().id()).isNotBlank();
    assertThat(result).contains(saved().id());
    verify(service).schedule(any());
  }

  @Test
  @DisplayName("a one-shot task is saved with an id too")
  void oneShotTaskCarriesAnId() {
    final var fireAt = Instant.now().plus(1, ChronoUnit.HOURS);

    tool.createScheduledTask("ping me", null, fireAt.toString(), null, null, null, context);

    assertThat(saved().id()).isNotBlank();
    verify(service).schedule(any());
  }

  @Test
  @DisplayName("two tasks do not share an id")
  void idsAreDistinct() {
    tool.createScheduledTask("first", "0 0 9 * * MON", null, null, null, null, context);
    tool.createScheduledTask("second", "0 0 9 * * MON", null, null, null, null, context);

    final var captor = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(repo, org.mockito.Mockito.times(2)).save(captor.capture());
    assertThat(captor.getAllValues().get(0).id()).isNotEqualTo(captor.getAllValues().get(1).id());
  }

  @Test
  @DisplayName("an invalid cron is refused, not stored as if it were an expression")
  void invalidCronIsRefused() {
    final var result =
        tool.createScheduledTask("summarise", "not a cron", null, null, null, null, context);

    assertThat(result).startsWith("Error:");
    verify(repo, never()).save(any());
    verify(service, never()).schedule(any());
  }

  @Test
  @DisplayName("a sub-minute cron is raised to the minimum interval rather than refused")
  void subMinuteCronIsRaised() {
    final var result =
        tool.createScheduledTask("poll", "0 */1 * * * *", null, null, null, null, context);

    assertThat(saved().cronExpression()).isEqualTo("0 */5 * * * *");
    assertThat(result).contains("raised to the smallest one allowed");
  }

  @Test
  @DisplayName("a task is only background when it was asked to be, on either schedule")
  void backgroundIsCarriedOntoTheTask() {
    tool.createScheduledTask(
        "say nothing unless X", "0 0 9 * * MON", null, null, true, null, context);
    assertThat(saved().background()).isTrue();

    final var fireAt = Instant.now().plus(1, ChronoUnit.HOURS);
    tool.createScheduledTask("send the report", null, fireAt.toString(), null, true, null, context);
    assertThat(saved().background()).isTrue();

    tool.createScheduledTask(
        "summarise the thread", "0 0 9 * * MON", null, null, null, null, context);
    assertThat(saved().background()).isNotEqualTo(true);
  }

  @Test
  @DisplayName("a background task says so, so the model can tell the user what it made")
  void backgroundIsMentionedInTheConfirmation() {
    final var result =
        tool.createScheduledTask(
            "say nothing unless X", "0 0 9 * * MON", null, null, true, null, context);

    assertThat(result).contains("runs in the background");
  }

  @Test
  @DisplayName("only what an update names is changed; the rest of the task is left alone")
  void updateKeepsWhatItWasNotGiven() {
    final var existing = active().cronExpression("0 0 9 * * MON").taskText("old text").build();
    when(repo.findById("t1")).thenReturn(Optional.of(existing));

    tool.updateScheduledTask("t1", "new text", null, null, context);

    assertThat(saved().taskText()).isEqualTo("new text");
    assertThat(saved().cronExpression()).isEqualTo("0 0 9 * * MON");
    // Rescheduled even though the time did not move: the timer holds the task as it was, so
    // otherwise the new text would first be used after a restart.
    verify(service).reschedule(any());
  }

  @Test
  @DisplayName("giving a one-off time to a recurring task drops the cron, rather than keeping both")
  void updateSwitchesBetweenSchedules() {
    final var existing = active().cronExpression("0 0 9 * * MON").build();
    when(repo.findById("t1")).thenReturn(Optional.of(existing));
    final var fireAt = Instant.now().plus(1, ChronoUnit.HOURS);

    tool.updateScheduledTask("t1", null, null, fireAt.toString(), context);

    assertThat(saved().cronExpression()).isNull();
    assertThat(saved().scheduledAt()).isEqualTo(fireAt);
    verify(service).reschedule(any());
  }

  @Test
  @DisplayName("an update is refused whole when its new schedule is invalid")
  void updateRefusesAnInvalidCron() {
    when(repo.findById("t1")).thenReturn(Optional.of(active().taskText("keep").build()));

    final var result = tool.updateScheduledTask("t1", "new text", "not a cron", null, context);

    assertThat(result).startsWith("Error:");
    // Not even the text, which was valid: half an update leaves the task saying something new on a
    // schedule the caller thinks it no longer has.
    verify(repo, never()).save(any());
    verify(service, never()).reschedule(any());
  }

  @Test
  @DisplayName("a task belonging to someone else is not changed")
  void updateRefusesAnotherUsersTask() {
    when(repo.findById("t1")).thenReturn(Optional.of(active().userId("ou_someone_else").build()));

    final var result = tool.updateScheduledTask("t1", "new text", null, null, context);

    assertThat(result).startsWith("Error:");
    verify(repo, never()).save(any());
  }

  @Test
  @DisplayName("a cancelled task is not brought back to life by an update")
  void updateRefusesATaskThatIsNoLongerActive() {
    when(repo.findById("t1"))
        .thenReturn(Optional.of(active().status(ScheduledTask.Status.CANCELLED).build()));

    final var result = tool.updateScheduledTask("t1", "new text", null, null, context);

    assertThat(result).startsWith("Error:");
    verify(repo, never()).save(any());
    verify(service, never()).reschedule(any());
  }

  @Test
  @DisplayName("an update naming nothing to change says so instead of rescheduling for nothing")
  void updateWithNothingToChange() {
    when(repo.findById("t1")).thenReturn(Optional.of(active().build()));

    final var result = tool.updateScheduledTask("t1", null, null, null, context);

    assertThat(result).startsWith("Error:");
    verify(repo, never()).save(any());
    verify(service, never()).reschedule(any());
  }

  private static ScheduledTask.ScheduledTaskBuilder active() {
    return ScheduledTask.builder()
        .id("t1")
        .userId("ou_1")
        .taskText("the task")
        .status(ScheduledTask.Status.ACTIVE);
  }

  @Test
  @DisplayName("a task remembers the group and tenant it was created in, not only its creator")
  void scopesAreStored() {
    tool.createScheduledTask("summarise", "0 0 9 * * MON", null, null, null, null, context);

    assertThat(saved().groupId()).isEqualTo("oc_group");
    assertThat(saved().tenantId()).isEqualTo("tenant_1");
  }

  private ScheduledTask saved() {
    final var captor = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(repo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    return captor.getValue();
  }
}
