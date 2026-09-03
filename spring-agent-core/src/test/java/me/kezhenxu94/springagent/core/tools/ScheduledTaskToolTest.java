package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskEdit;
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
    // The rules an edit is held to are ScheduledTaskEdit's and are tested there. What is left for
    // the tool is which edit it builds out of its parameters, so the service is only a recorder.
    when(service.edit(any(), any()))
        .thenAnswer(
            invocation ->
                new ScheduledTaskEdit.Result(invocation.getArgument(0), List.of("changed"), ""));
  }

  @Test
  @DisplayName("a cron task is saved with an id, since no backend generates one")
  void cronTaskCarriesAnId() {
    final var result =
        tool.createScheduledTask(
            "A task", "summarise the thread", "0 0 9 * * MON", null, null, null, null, context);

    assertThat(saved().id()).isNotBlank();
    assertThat(result).contains(saved().id());
    verify(service).schedule(any());
  }

  @Test
  @DisplayName("a one-shot task is saved with an id too")
  void oneShotTaskCarriesAnId() {
    final var fireAt = Instant.now().plus(1, ChronoUnit.HOURS);

    tool.createScheduledTask(
        "A task", "ping me", null, fireAt.toString(), null, null, null, context);

    assertThat(saved().id()).isNotBlank();
    verify(service).schedule(any());
  }

  @Test
  @DisplayName("two tasks do not share an id")
  void idsAreDistinct() {
    tool.createScheduledTask("A task", "first", "0 0 9 * * MON", null, null, null, null, context);
    tool.createScheduledTask("A task", "second", "0 0 9 * * MON", null, null, null, null, context);

    final var captor = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(repo, org.mockito.Mockito.times(2)).save(captor.capture());
    assertThat(captor.getAllValues().get(0).id()).isNotEqualTo(captor.getAllValues().get(1).id());
  }

  @Test
  @DisplayName("an invalid cron is refused, not stored as if it were an expression")
  void invalidCronIsRefused() {
    final var result =
        tool.createScheduledTask(
            "A task", "summarise", "not a cron", null, null, null, null, context);

    assertThat(result).startsWith("Error:");
    verify(repo, never()).save(any());
    verify(service, never()).schedule(any());
  }

  @Test
  @DisplayName("a sub-minute cron is raised to the minimum interval rather than refused")
  void subMinuteCronIsRaised() {
    final var result =
        tool.createScheduledTask(
            "A task", "poll", "0 */1 * * * *", null, null, null, null, context);

    assertThat(saved().cronExpression()).isEqualTo("0 */5 * * * *");
    assertThat(result).contains("raised to the smallest one allowed");
  }

  @Test
  @DisplayName("a task is only background when it was asked to be, on either schedule")
  void backgroundIsCarriedOntoTheTask() {
    tool.createScheduledTask(
        "A task", "say nothing unless X", "0 0 9 * * MON", null, null, true, null, context);
    assertThat(saved().background()).isTrue();

    final var fireAt = Instant.now().plus(1, ChronoUnit.HOURS);
    tool.createScheduledTask(
        "A task", "send the report", null, fireAt.toString(), null, true, null, context);
    assertThat(saved().background()).isTrue();

    tool.createScheduledTask(
        "A task", "summarise the thread", "0 0 9 * * MON", null, null, null, null, context);
    assertThat(saved().background()).isNotEqualTo(true);
  }

  @Test
  @DisplayName("a background task says so, so the model can tell the user what it made")
  void backgroundIsMentionedInTheConfirmation() {
    final var result =
        tool.createScheduledTask(
            "A task", "say nothing unless X", "0 0 9 * * MON", null, null, true, null, context);

    assertThat(result).contains("runs in the background");
  }

  @Test
  @DisplayName("an update passes on only what it was given, so the rest of the task is left alone")
  void updatePassesOnOnlyWhatItWasGiven() {
    when(repo.findById("t1")).thenReturn(Optional.of(active().build()));

    tool.updateScheduledTask("t1", null, "new text", null, null, null, null, null, context);

    assertThat(edited().taskText()).isEqualTo("new text");
    assertThat(edited().title()).isNull();
    assertThat(edited().cronExpression()).isNull();
    assertThat(edited().scheduledAt()).isNull();
    assertThat(edited().expiresAt()).isNull();
    assertThat(edited().background()).isNull();
    assertThat(edited().maxRuns()).isNull();
  }

  @Test
  @DisplayName("every field of a task's definition can be changed at once")
  void updateCarriesEveryField() {
    when(repo.findById("t1")).thenReturn(Optional.of(active().build()));
    final var until = Instant.now().plus(30, ChronoUnit.DAYS);

    tool.updateScheduledTask(
        "t1", "New name", "new text", "0 0 9 * * MON", null, until.toString(), true, 5, context);

    assertThat(edited())
        .isEqualTo(
            new ScheduledTaskEdit(
                "New name", "new text", "0 0 9 * * MON", null, until.toString(), true, 5));
  }

  @Test
  @DisplayName("an empty string is a field the model left out, not a schedule it meant to set")
  void updateTreatsBlanksAsAbsent() {
    when(repo.findById("t1")).thenReturn(Optional.of(active().build()));

    tool.updateScheduledTask("t1", null, "new text", "", "", "", null, null, context);

    assertThat(edited().cronExpression()).isNull();
    assertThat(edited().scheduledAt()).isNull();
    assertThat(edited().expiresAt()).isNull();
  }

  @Test
  @DisplayName("what the edit refuses is reported as it stands, and nothing is written")
  void updateReportsWhyAnEditWasRefused() {
    when(repo.findById("t1")).thenReturn(Optional.of(active().build()));
    when(service.edit(any(), any()))
        .thenThrow(new IllegalArgumentException("cron expression 'nope' is invalid: nope"));

    final var result =
        tool.updateScheduledTask("t1", null, null, "nope", null, null, null, null, context);

    assertThat(result).startsWith("Error:").contains("is invalid");
    verify(repo, never()).save(any());
  }

  @Test
  @DisplayName("a task belonging to someone else is not changed")
  void updateRefusesAnotherUsersTask() {
    when(repo.findById("t1")).thenReturn(Optional.of(active().userId("ou_someone_else").build()));

    final var result =
        tool.updateScheduledTask("t1", null, "new text", null, null, null, null, null, context);

    assertThat(result).startsWith("Error:");
    verify(service, never()).edit(any(), any());
  }

  @Test
  @DisplayName("a cancelled task is not brought back to life by an update")
  void updateRefusesATaskThatIsNoLongerActive() {
    when(repo.findById("t1"))
        .thenReturn(Optional.of(active().status(ScheduledTask.Status.CANCELLED).build()));

    final var result =
        tool.updateScheduledTask("t1", null, "new text", null, null, null, null, null, context);

    assertThat(result).startsWith("Error:");
    verify(service, never()).edit(any(), any());
  }

  /** The edit the tool built out of its parameters, which is the whole of what it decides. */
  private ScheduledTaskEdit edited() {
    final var captor = ArgumentCaptor.forClass(ScheduledTaskEdit.class);
    verify(service).edit(any(), captor.capture());
    return captor.getValue();
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
    tool.createScheduledTask(
        "A task", "summarise", "0 0 9 * * MON", null, null, null, null, context);

    assertThat(saved().groupId()).isEqualTo("oc_group");
    assertThat(saved().tenantId()).isEqualTo("tenant_1");
  }

  private ScheduledTask saved() {
    final var captor = ArgumentCaptor.forClass(ScheduledTask.class);
    verify(repo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    return captor.getValue();
  }
}
