package me.kezhenxu94.springagent.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules about what a scheduled task's definition may be, which both ways of editing one — the
 * agent's tool and the browser's PATCH — are held to.
 */
class ScheduledTaskEditTest {

  private final ScheduledTask task =
      ScheduledTask.builder()
          .id("t1")
          .userId("ou_1")
          .title("Thread digest")
          .taskText("summarise the thread")
          .cronExpression("0 0 9 * * MON")
          .expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
          .maxRuns(10)
          .runCount(3)
          .background(true)
          .nextFireAt(Instant.parse("2030-01-01T00:00:00Z"))
          .status(ScheduledTask.Status.ACTIVE)
          .build();

  @Test
  @DisplayName("a field left out keeps what the task already had")
  void absentMeansKeep() {
    final var edited =
        new ScheduledTaskEdit(null, "new text", null, null, null, null, null).applyTo(task).task();

    assertThat(edited.cronExpression()).isEqualTo("0 0 9 * * MON");
    assertThat(edited.expiresAt()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
    assertThat(edited.maxRuns()).isEqualTo(10);
    assertThat(edited.background()).isTrue();
  }

  @Test
  @DisplayName("a task can be renamed without its prompt being restated")
  void renaming() {
    final var result =
        new ScheduledTaskEdit("  Morning digest  ", null, null, null, null, null, null)
            .applyTo(task);

    assertThat(result.task().title()).isEqualTo("Morning digest");
    assertThat(result.task().taskText()).isEqualTo("summarise the thread");
    // A rename is not a prompt change, so it does not take the write path that only a prompt may.
    assertThat(new ScheduledTaskEdit("new name", null, null, null, null, null, null).textOnly())
        .isFalse();
  }

  @Test
  @DisplayName("a task cannot be left nameless, nor named at prompt length")
  void titleIsAName() {
    assertThatThrownBy(
            () -> new ScheduledTaskEdit("  ", null, null, null, null, null, null).applyTo(task))
        .hasMessageContaining("needs a name");
    assertThatThrownBy(
            () ->
                new ScheduledTaskEdit(
                        "x".repeat(ScheduledTaskEdit.MAX_TITLE + 1),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)
                    .applyTo(task))
        .hasMessageContaining("limited to");
  }

  @Test
  @DisplayName("what has already happened is never part of an edit")
  void anEditNeverTouchesWhatHasHappened() {
    final var edited =
        new ScheduledTaskEdit("New name", "new text", "0 0 10 * * MON", null, "never", false, 20)
            .applyTo(task)
            .task();

    assertThat(edited.runCount()).isEqualTo(3);
    assertThat(edited.userId()).isEqualTo("ou_1");
    assertThat(edited.id()).isEqualTo("t1");
  }

  @Test
  @DisplayName("giving a one-off time to a recurring task drops the cron, rather than keeping both")
  void oneScheduleAtATime() {
    final var fireAt = Instant.now().plus(1, ChronoUnit.HOURS);

    final var edited =
        new ScheduledTaskEdit(null, null, null, fireAt.toString(), null, null, null)
            .applyTo(task)
            .task();

    assertThat(edited.cronExpression()).isNull();
    assertThat(edited.scheduledAt()).isEqualTo(fireAt);
  }

  @Test
  @DisplayName("naming both schedules is refused, since a task carries one")
  void bothSchedulesAtOnce() {
    final var fireAt = Instant.now().plus(1, ChronoUnit.HOURS).toString();

    assertThatThrownBy(
            () ->
                new ScheduledTaskEdit(null, null, "0 0 9 * * MON", fireAt, null, null, null)
                    .applyTo(task))
        .hasMessageContaining("not both");
  }

  @Test
  @DisplayName("an edit is refused whole when any part of it is invalid")
  void refusedWhole() {
    assertThatThrownBy(
            () ->
                new ScheduledTaskEdit(null, "new text", "not a cron", null, null, null, null)
                    .applyTo(task))
        .hasMessageContaining("is invalid");
  }

  @Test
  @DisplayName("a schedule shorter than the floor is raised to it, and the edit says so")
  void intervalIsRaised() {
    final var result =
        new ScheduledTaskEdit(null, null, "0 */1 * * * *", null, null, null, null).applyTo(task);

    assertThat(result.task().cronExpression()).isEqualTo("0 */5 * * * *");
    assertThat(result.note()).contains("raised");
  }

  @Test
  @DisplayName("\"never\" takes an expiry off, where a null would only have kept it")
  void expiryCanBeTakenOff() {
    final var result =
        new ScheduledTaskEdit(null, null, null, null, ScheduledTaskEdit.NEVER, null, null)
            .applyTo(task);

    assertThat(result.task().expiresAt()).isNull();
    assertThat(result.changes()).contains("it no longer expires");
  }

  @Test
  @DisplayName(
      "zero stops the firings being counted, where a null would only have kept the ceiling")
  void ceilingCanBeTakenOff() {
    final var result =
        new ScheduledTaskEdit(null, null, null, null, null, null, ScheduledTaskEdit.UNLIMITED)
            .applyTo(task);

    assertThat(result.task().maxRuns()).isNull();
  }

  @Test
  @DisplayName("a ceiling below the firings already had is allowed, and ends the task")
  void ceilingMayBeLoweredPastWhatHasRun() {
    // Enforced by ScheduledTaskService#fire on the next sweep rather than here: this is a
    // legitimate way to stop a task after the run it is having now.
    assertThat(
            new ScheduledTaskEdit(null, null, null, null, null, null, 1)
                .applyTo(task)
                .task()
                .maxRuns())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("a negative ceiling is refused, since zero is already the word for uncounted")
  void negativeCeiling() {
    assertThatThrownBy(
            () -> new ScheduledTaskEdit(null, null, null, null, null, null, -1).applyTo(task))
        .hasMessageContaining("at least 1");
  }

  @Test
  @DisplayName("a task cannot be left with nothing to do")
  void textCannotBeEmptied() {
    assertThatThrownBy(
            () -> new ScheduledTaskEdit(null, "   ", null, null, null, null, null).applyTo(task))
        .hasMessageContaining("something to do");
  }

  @Test
  @DisplayName("a prompt longer than the column is refused rather than truncated")
  void textIsCapped() {
    final var tooLong = "x".repeat(ScheduledTaskEdit.MAX_TASK_TEXT + 1);

    assertThatThrownBy(
            () -> new ScheduledTaskEdit(null, tooLong, null, null, null, null, null).applyTo(task))
        .hasMessageContaining("limited to");
  }

  @Test
  @DisplayName("a time in the past is refused, on either of the two fields that carry one")
  void timesMustBeInTheFuture() {
    final var past = Instant.now().minus(1, ChronoUnit.HOURS).toString();

    assertThatThrownBy(
            () -> new ScheduledTaskEdit(null, null, null, past, null, null, null).applyTo(task))
        .hasMessageContaining("must be in the future");
    assertThatThrownBy(
            () -> new ScheduledTaskEdit(null, null, null, null, past, null, null).applyTo(task))
        .hasMessageContaining("must be in the future");
  }

  @Test
  @DisplayName("an edit naming nothing says so, rather than rewriting the task with itself")
  void namingNothing() {
    final var nothing = new ScheduledTaskEdit(null, null, null, null, null, null, null);

    assertThat(nothing.namesNothing()).isTrue();
    assertThatThrownBy(() -> nothing.applyTo(task)).hasMessageContaining("nothing to change");
  }

  @Test
  @DisplayName(
      "only a change to the prompt alone counts as one, since only that skips a reschedule")
  void textOnly() {
    assertThat(new ScheduledTaskEdit(null, "new", null, null, null, null, null).textOnly())
        .isTrue();
    assertThat(new ScheduledTaskEdit(null, "new", null, null, null, true, null).textOnly())
        .isFalse();
    assertThat(new ScheduledTaskEdit(null, null, null, null, null, true, null).textOnly())
        .isFalse();
  }
}
