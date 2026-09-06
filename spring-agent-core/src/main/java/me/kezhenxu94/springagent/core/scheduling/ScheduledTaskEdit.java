package me.kezhenxu94.springagent.core.scheduling;

import com.google.common.base.Strings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;

/**
 * A change to a scheduled task's definition, and the rules about what a definition may be.
 *
 * <p>Here rather than in the tool that used to hold them, because there are now two ways to change
 * a task — the agent's {@code UpdateScheduledTask}, and a person editing one in the browser — and
 * the alternative is two sets of rules about what a schedule may be. A cron floor that holds only
 * when the model sets it, or a text cap enforced on one route and truncating silently on the other,
 * is worse than either rule on its own.
 *
 * <p>What can be changed is the task's <em>definition</em>: what it is called, what it does, when,
 * until when, how often, and whether anybody is expected to be there. What cannot is who owns it
 * and which conversation it fires into — those are not an edit, they are a different task — nor
 * {@code runCount} and {@code nextFireAt}, which belong to {@link ScheduledTaskSweeper} and are the
 * one account of what has actually happened.
 *
 * <p><b>Null means keep.</b> Every field is optional and an absent one leaves the task as it
 * stands, so changing the time does not restate the prompt. That leaves no way to say "no longer
 * has one" for the two fields where absence is itself a value, hence one sentinel each: {@link
 * #NEVER} for an expiry, {@link #UNLIMITED} for a firing count. A sentinel rather than a second
 * boolean field per value because the agent reaches this through tool parameters, where there is no
 * difference between a null it chose and a null it omitted.
 */
public record ScheduledTaskEdit(
    String title,
    String taskText,
    String cronExpression,
    String scheduledAt,
    String expiresAt,
    Boolean background,
    Integer maxRuns) {

  /** The length {@code ScheduledTask#taskText} is declared at, which is what actually stores it. */
  public static final int MAX_TASK_TEXT = 8192;

  /**
   * As long as a title is any use for what a title is for. The column takes far more — it is a
   * plain varchar — but a title is read in a sidebar row that truncates at a fraction of this, so
   * anything past it is a prompt in the wrong field.
   */
  public static final int MAX_TITLE = 120;

  /** As {@code expiresAt}: the task stops having an expiry rather than keeping the one it has. */
  public static final String NEVER = "never";

  /** As {@code maxRuns}: the task stops being counted rather than keeping its ceiling. */
  public static final int UNLIMITED = 0;

  public boolean namesNothing() {
    return title == null
        && taskText == null
        && cronExpression == null
        && scheduledAt == null
        && expiresAt == null
        && background == null
        && maxRuns == null;
  }

  /**
   * Whether this edit touches only the prompt, which is the one change that needs neither a rewrite
   * of the whole row nor a new next occurrence. See {@code ScheduledTaskService#edit}.
   */
  public boolean textOnly() {
    return taskText != null
        && title == null
        && cronExpression == null
        && scheduledAt == null
        && expiresAt == null
        && background == null
        && maxRuns == null;
  }

  /**
   * The task as this edit would leave it, along with what to tell whoever asked for it.
   *
   * <p>Validated whole before anything is built: half an edit leaves a task saying something new on
   * a schedule the caller believes it no longer has, which is worse than an edit that did not
   * happen.
   *
   * @throws IllegalArgumentException with a message meant to be read by whoever asked — a person in
   *     a dialog, or the model as a tool result
   */
  public Result applyTo(final ScheduledTask task, final CoreMessages messages) {
    if (namesNothing()) {
      throw new IllegalArgumentException(messages.get("scheduled-task-edit-nothing"));
    }
    final var hasCron = !Strings.isNullOrEmpty(cronExpression);
    final var hasScheduledAt = !Strings.isNullOrEmpty(scheduledAt);
    if (hasCron && hasScheduledAt) {
      throw new IllegalArgumentException(messages.get("scheduled-task-edit-both-schedules"));
    }

    final var updated = task.toBuilder();
    final var changes = new ArrayList<String>();
    var note = "";

    if (title != null) {
      final var named = title.trim();
      if (named.isEmpty()) {
        throw new IllegalArgumentException(messages.get("scheduled-task-edit-no-title"));
      }
      if (named.length() > MAX_TITLE) {
        throw new IllegalArgumentException(
            messages.get("scheduled-task-edit-title-too-long", MAX_TITLE));
      }
      updated.title(named);
      changes.add(messages.get("scheduled-task-edit-renamed", named));
    }

    if (taskText != null) {
      final var text = taskText.trim();
      if (text.isEmpty()) {
        throw new IllegalArgumentException(messages.get("scheduled-task-edit-no-text"));
      }
      if (text.length() > MAX_TASK_TEXT) {
        throw new IllegalArgumentException(
            messages.get("scheduled-task-edit-text-too-long", MAX_TASK_TEXT));
      }
      updated.taskText(text);
      changes.add(messages.get("scheduled-task-edit-retexted", text));
    }

    if (hasCron) {
      final var validated = CronSchedules.validated(cronExpression);
      // Both fields written, not only the one given: a task carries one schedule, and leaving the
      // old scheduledAt on a task that has just been made recurring is a second one that
      // ScheduledTaskService would have to choose between.
      updated.cronExpression(validated).scheduledAt(null);
      changes.add(messages.get("scheduled-task-edit-cron", validated));
      if (!validated.equals(cronExpression)) {
        // The space is here rather than in the bundle: a leading space in a properties value is
        // stripped when the bundle is read, so a translation cannot carry its own separator.
        note = " " + messages.get("scheduled-task-interval-raised", validated);
      }
    } else if (hasScheduledAt) {
      final var fireAt = future(scheduledAt, "scheduledAt", messages);
      updated.cronExpression(null).scheduledAt(fireAt);
      changes.add(messages.get("scheduled-task-edit-once", fireAt));
    }

    if (expiresAt != null) {
      if (expiresAt.equalsIgnoreCase(NEVER)) {
        updated.expiresAt(null);
        changes.add(messages.get("scheduled-task-edit-no-expiry"));
      } else {
        final var until = future(expiresAt, "expiresAt", messages);
        updated.expiresAt(until);
        changes.add(messages.get("scheduled-task-edit-expires", until));
      }
    }

    if (background != null) {
      updated.background(background);
      changes.add(
          messages.get(
              background ? "scheduled-task-edit-background" : "scheduled-task-edit-foreground"));
    }

    if (maxRuns != null) {
      if (maxRuns < UNLIMITED) {
        throw new IllegalArgumentException(
            messages.get("scheduled-task-edit-bad-max-runs", UNLIMITED));
      }
      if (maxRuns == UNLIMITED) {
        updated.maxRuns(null);
        changes.add(messages.get("scheduled-task-edit-unlimited"));
      } else {
        updated.maxRuns(maxRuns);
        changes.add(messages.get("scheduled-task-edit-max-runs", maxRuns));
      }
    }

    return new Result(updated.build(), List.copyOf(changes), note);
  }

  /**
   * A moment that has not gone yet. Both times a task carries are one — an expiry in the past
   * retires the task on the next sweep, and a one-off in the past fires immediately — so a typo in
   * the year is a task that quietly does the opposite of what was asked for rather than an error.
   */
  private static Instant future(
      final String value, final String field, final CoreMessages messages) {
    final Instant parsed;
    try {
      parsed = Instant.parse(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(messages.get("scheduled-task-edit-bad-time", field), e);
    }
    if (parsed.isBefore(Instant.now())) {
      throw new IllegalArgumentException(messages.get("scheduled-task-edit-past-time", field));
    }
    return parsed;
  }

  /** The edited task, what changed in words, and anything the rules did to the request. */
  public record Result(ScheduledTask task, List<String> changes, String note) {}
}
