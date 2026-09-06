package me.kezhenxu94.springagent.core.tools;

import com.google.common.base.Strings;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import me.kezhenxu94.springagent.core.scheduling.CronSchedules;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskEdit;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class ScheduledTaskTool {

  final ScheduledTaskRepo scheduledTaskRepo;
  final ScheduledTaskService scheduledTaskService;

  /**
   * Everything this hands back to the model, in the workspace's language.
   *
   * <p>These are results and instructions a model reads and then writes an answer from, so English
   * here is English in the answer — and, before that, in the reasoning the user watches. A task
   * created in Chinese and confirmed in English is the whole of the complaint.
   */
  final CoreMessages messages;

  @Tool(
      name = "CreateScheduledTask",
      description =
"""
Create a scheduled task. For a recurring one, give a 6-field Spring cron expression \
(second minute hour day-of-month month day-of-week); for example "0 0 1 * * MON" is \
every Monday at 01:00, "0 */30 * * * *" is every 30 minutes, and "0 0 0 * * MON-FRI" \
is midnight on weekdays. For a one-off, give scheduledAt as an ISO-8601 timestamp \
with an offset, such as "2025-01-15T10:00:00+08:00".
Resolve anything relative ("tomorrow morning") with CurrentDateTime first, since the \
times here are absolute. Give either cronExpression or scheduledAt, never both.
For a task that runs a set number of times ("remind me every 10 minutes, 10 times"), give maxRuns \
rather than working out when it would stop: the scheduler keeps the count, so it is exact.
Give a title as well as the prompt: the title is what the user sees in their list of scheduled \
tasks, so write it as a person would name the job rather than as an instruction to you.
""")
  public String createScheduledTask(
      @ToolParam(
              description =
                  "A short name for the task, a few words, as it will be listed for the user — for"
                      + " example \"Morning deploy check\" or \"Weekly numbers to #ops\". Not an"
                      + " instruction and not a sentence: the prompt below is where what to do"
                      + " goes.")
          final String title,
      @ToolParam(description = "The prompt to send to the agent when the task fires")
          final String taskText,
      @ToolParam(
              description =
                  "6-field Spring cron expression for a recurring task; null for a one-off",
              required = false)
          final String cronExpression,
      @ToolParam(
              description =
                  "When a one-off task fires, ISO-8601 with an offset (for example"
                      + " \"2025-01-15T10:00:00+08:00\"); null for a recurring task",
              required = false)
          final String scheduledAt,
      @ToolParam(
              description =
                  "When the task stops firing, ISO-8601 with an offset (for example"
                      + " \"2025-12-31T23:59:59+08:00\"). Pass \"never\" for no expiry at all;"
                      + " omit it to expire in 7 days.",
              required = false)
          final String expiresAt,
      @ToolParam(
              description =
                  "Run each firing in the background, out of sight: nothing is posted for it and"
                      + " what you write is delivered nowhere, so the user hears about it only"
                      + " through a message the task itself sends. Only a firing that failed is"
                      + " reported. Set this when the task decides for itself whether anything is"
                      + " worth saying (\"check X, and only if Y send a summary to Z\") or when it"
                      + " already sends its own message (\"every morning send me the numbers\") —"
                      + " otherwise the user gets that message and a report of the run on top of"
                      + " it. Say in taskText who to send to. Leave this out for a task whose"
                      + " answer is the point: the firing then replies in the conversation it was"
                      + " created in, as a normal run does.",
              required = false)
          final Boolean background,
      @ToolParam(
              description =
                  "How many times the task fires in total, after which it stops by itself; null for"
                      + " a task that fires until it expires or is cancelled",
              required = false)
          final Integer maxRuns,
      final ToolContext context) {

    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    final var rootMessageId = ToolContexts.require(context, ToolContexts.ROOT_MESSAGE_ID);
    final var chatId = ToolContexts.get(context, ToolContexts.CHAT_ID);
    final var chatType = ToolContexts.get(context, ToolContexts.CHAT_TYPE);
    final var groupId = ToolContexts.get(context, ToolContexts.GROUP_ID);
    final var tenantId = ToolContexts.get(context, ToolContexts.TENANT_ID);

    final var hasCron = !Strings.isNullOrEmpty(cronExpression);
    final var hasScheduledAt = !Strings.isNullOrEmpty(scheduledAt);

    if (hasCron && hasScheduledAt) {
      return messages.get("scheduled-task-both-schedules");
    }
    if (!hasCron && !hasScheduledAt) {
      return messages.get("scheduled-task-no-schedule");
    }

    final Instant resolvedExpiresAt;
    if (Strings.isNullOrEmpty(expiresAt)) {
      resolvedExpiresAt = Instant.now().plus(Duration.ofDays(7));
    } else if (expiresAt.equalsIgnoreCase("never")) {
      resolvedExpiresAt = null;
    } else {
      Instant parsed;
      try {
        parsed = Instant.parse(expiresAt);
      } catch (Exception e) {
        return messages.get("scheduled-task-bad-expires-at");
      }
      if (parsed.isBefore(Instant.now())) {
        return messages.get("scheduled-task-past-expires-at");
      }
      resolvedExpiresAt = parsed;
    }

    if (maxRuns != null && maxRuns < 1) {
      return messages.get("scheduled-task-bad-max-runs");
    }

    // The column's own limit. Checked here rather than left to the database, which on JPA would
    // truncate the prompt to something that still fires and does the wrong thing.
    if (Strings.nullToEmpty(taskText).trim().length() > ScheduledTaskEdit.MAX_TASK_TEXT) {
      return messages.get("scheduled-task-text-too-long", ScheduledTaskEdit.MAX_TASK_TEXT);
    }
    if (Strings.isNullOrEmpty(title) || title.isBlank()) {
      return messages.get("scheduled-task-no-title");
    }
    if (title.trim().length() > ScheduledTaskEdit.MAX_TITLE) {
      return messages.get("scheduled-task-title-too-long", ScheduledTaskEdit.MAX_TITLE);
    }

    // Joined with a space rather than each note carrying a leading one: a leading space in a
    // properties value is stripped when the bundle is read, so a note that owned its own separator
    // would run into the sentence before it — and only in the language whose translation kept it.
    final var runsNote = maxRuns == null ? "" : messages.get("scheduled-task-runs-note", maxRuns);

    final var expiryNote =
        resolvedExpiresAt == null
            ? messages.get("scheduled-task-never-expires")
            : messages.get("scheduled-task-expires-at", resolvedExpiresAt);

    final var backgroundNote =
        Boolean.TRUE.equals(background) ? messages.get("scheduled-task-background-note") : "";

    if (hasCron) {
      final String validated;
      try {
        validated = CronSchedules.validated(cronExpression);
      } catch (IllegalArgumentException e) {
        return messages.get("scheduled-task-error", e.getMessage());
      }
      final var task =
          scheduledTaskRepo.save(
              ScheduledTask.builder()
                  .id(newTaskId())
                  .userId(userId)
                  .chatId(chatId)
                  .chatType(chatType)
                  .groupId(groupId)
                  .tenantId(tenantId)
                  .rootMessageId(rootMessageId)
                  .title(title.trim())
                  .taskText(taskText)
                  .cronExpression(validated)
                  .expiresAt(resolvedExpiresAt)
                  .background(background)
                  .maxRuns(maxRuns)
                  .status(ScheduledTask.Status.ACTIVE)
                  .build());
      scheduledTaskService.schedule(task);
      final var overrideNote =
          validated.equals(cronExpression)
              ? ""
              : " " + messages.get("scheduled-task-interval-raised", validated);
      return messages.get(
          "scheduled-task-created-recurring",
          title.trim(),
          validated,
          task.id(),
          notes(expiryNote, runsNote, overrideNote, backgroundNote));
    } else {
      final Instant fireAt;
      try {
        fireAt = Instant.parse(scheduledAt);
      } catch (Exception e) {
        return messages.get("scheduled-task-bad-scheduled-at");
      }
      if (fireAt.isBefore(Instant.now())) {
        return messages.get("scheduled-task-past-scheduled-at");
      }
      final var task =
          scheduledTaskRepo.save(
              ScheduledTask.builder()
                  .id(newTaskId())
                  .userId(userId)
                  .chatId(chatId)
                  .chatType(chatType)
                  .groupId(groupId)
                  .tenantId(tenantId)
                  .rootMessageId(rootMessageId)
                  .title(title.trim())
                  .taskText(taskText)
                  .scheduledAt(fireAt)
                  .expiresAt(resolvedExpiresAt)
                  .background(background)
                  .maxRuns(maxRuns)
                  .status(ScheduledTask.Status.ACTIVE)
                  .build());
      scheduledTaskService.schedule(task);
      return messages.get(
          "scheduled-task-created-once",
          title.trim(),
          fireAt,
          task.id(),
          notes(expiryNote, runsNote, backgroundNote));
    }
  }

  @Tool(
      name = "ListScheduledTasks",
      description = "List active scheduled tasks for the current user")
  public String listScheduledTasks(final ToolContext context) {
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    final List<ScheduledTask> tasks =
        scheduledTaskRepo.findByUserIdAndStatus(userId, ScheduledTask.Status.ACTIVE);
    if (tasks.isEmpty()) {
      return messages.get("scheduled-task-none");
    }
    return tasks.stream()
        .map(
            t ->
                "- id="
                    + t.id()
                    + " | title="
                    + name(t)
                    + " | task="
                    + t.taskText()
                    + " | schedule="
                    + (t.cronExpression() != null ? t.cronExpression() : t.scheduledAt())
                    + (t.maxRuns() == null
                        ? ""
                        : " | runs="
                            + (t.runCount() == null ? 0 : t.runCount())
                            + "/"
                            + t.maxRuns())
                    // Listed because it is editable: a model asked to "keep it going a bit longer"
                    // otherwise has to guess whether the task has an expiry to move at all.
                    + (t.expiresAt() == null ? "" : " | expires=" + t.expiresAt())
                    + (Boolean.TRUE.equals(t.background()) ? " | background" : ""))
        .collect(Collectors.joining("\n"));
  }

  @Tool(
      name = "UpdateScheduledTask",
      description =
"""
Change a scheduled task that already exists: what it does, when it fires, how long for, how many times, whether it runs unattended, or any combination of those.

Usage:
- taskId comes from ListScheduledTasks.
- Pass only what changes. Anything left out is kept as it is, so changing the time does not restate
  the task, and rewriting the task does not restate the time.
- The schedule is either cronExpression or scheduledAt, never both, and giving one replaces the
  other: a recurring task given a scheduledAt becomes a one-off, and a one-off given a
  cronExpression becomes recurring.
- Two fields need a word for "no longer has one", since leaving them out means "keep": pass
  expiresAt as "never" to take an expiry off, and maxRuns as 0 to stop counting the firings.
- Resolve anything relative ("move it to tomorrow morning") with CurrentDateTime first, since the
  times here are absolute.
- Only an active task the current user created can be changed. Use CreateScheduledTask for a new
  one, and CancelScheduledTask to stop one altogether.
""")
  public String updateScheduledTask(
      @ToolParam(description = "The task ID to change, as shown by ListScheduledTasks")
          final String taskId,
      @ToolParam(
              description =
                  "The new short name for the task, as the user sees it listed; null to keep the"
                      + " current one",
              required = false)
          final String title,
      @ToolParam(
              description = "The new prompt to send when the task fires; null to keep the current",
              required = false)
          final String taskText,
      @ToolParam(
              description =
                  "The new 6-field Spring cron expression, making the task recurring; null to keep"
                      + " the current schedule",
              required = false)
          final String cronExpression,
      @ToolParam(
              description =
                  "The new time for a one-off firing, ISO-8601 with an offset (for example"
                      + " \"2025-01-15T10:00:00+08:00\"); null to keep the current schedule",
              required = false)
          final String scheduledAt,
      @ToolParam(
              description =
                  "The new time the task stops firing, ISO-8601 with an offset; \"never\" to take"
                      + " its expiry off altogether; null to keep the current one",
              required = false)
          final String expiresAt,
      @ToolParam(
              description =
                  "Whether each firing now runs unattended — true to stop it posting anything of"
                      + " its own, false to make it reply in the conversation it was created in;"
                      + " null to leave that as it is. See CreateScheduledTask for what background"
                      + " firing means.",
              required = false)
          final Boolean background,
      @ToolParam(
              description =
                  "The new total number of firings, after which the task stops by itself; 0 to let"
                      + " it fire until it expires or is cancelled; null to keep the current"
                      + " ceiling. Counted against the firings it has already had, so lowering it"
                      + " below those ends the task.",
              required = false)
          final Integer maxRuns,
      final ToolContext context) {

    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    final var taskOpt = scheduledTaskRepo.findById(taskId);
    if (taskOpt.isEmpty()) {
      return messages.get("scheduled-task-unknown", taskId);
    }
    final var task = taskOpt.get();
    if (!task.userId().equals(userId)) {
      return messages.get("scheduled-task-not-yours-change");
    }
    if (task.status() != ScheduledTask.Status.ACTIVE) {
      return messages.get("scheduled-task-not-active", taskId, task.status());
    }

    // Empty strings mean the same as absent here. A model that has been told "null to keep the
    // current" reliably sends "" for some of them instead, and an empty cron would otherwise be a
    // schedule it never meant to set.
    final var edit =
        new ScheduledTaskEdit(
            Strings.emptyToNull(title),
            Strings.emptyToNull(taskText),
            Strings.emptyToNull(cronExpression),
            Strings.emptyToNull(scheduledAt),
            Strings.emptyToNull(expiresAt),
            background,
            maxRuns);

    final ScheduledTaskEdit.Result result;
    try {
      result = scheduledTaskService.edit(task, edit);
    } catch (IllegalArgumentException e) {
      // Nothing was written: the edit is validated whole before any of it is applied, so a task is
      // never left saying something new on a schedule the caller thinks it no longer has.
      return messages.get("scheduled-task-error", e.getMessage());
    }
    return messages.get(
        "scheduled-task-updated", taskId, String.join(", ", result.changes()), result.note());
  }

  @Tool(name = "CancelScheduledTask", description = "Cancel a scheduled task by ID")
  public String cancelScheduledTask(
      @ToolParam(description = "The task ID to cancel") final String taskId,
      final ToolContext context) {
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    final var taskOpt = scheduledTaskRepo.findById(taskId);
    if (taskOpt.isEmpty()) {
      return messages.get("scheduled-task-unknown", taskId);
    }
    final var task = taskOpt.get();
    if (!task.userId().equals(userId)) {
      return messages.get("scheduled-task-not-yours-cancel");
    }
    if (task.status() != ScheduledTask.Status.ACTIVE) {
      return messages.get("scheduled-task-already", taskId, task.status());
    }
    scheduledTaskRepo.save(task.toBuilder().status(ScheduledTask.Status.CANCELLED).build());
    scheduledTaskService.unschedule(taskId);
    return messages.get("scheduled-task-cancelled", taskId);
  }

  /** The notes that apply, as one sentence run, skipping the ones that came back empty. */
  private static String notes(final String... parts) {
    return java.util.Arrays.stream(parts)
        .filter(part -> !part.isEmpty())
        .collect(Collectors.joining(" "));
  }

  /**
   * What to call a task in a listing.
   *
   * <p>A task written before {@code title} existed has none — the schema is {@code ddl-auto} with
   * no migrations — and is still worth listing, so its prompt stands in. Shortened, because a
   * prompt runs to paragraphs and a listing is one line per task.
   */
  private static String name(final ScheduledTask task) {
    if (!Strings.isNullOrEmpty(task.title())) {
      return task.title();
    }
    final var text = Strings.nullToEmpty(task.taskText()).strip().replaceAll("\\s+", " ");
    return text.length() <= ScheduledTaskEdit.MAX_TITLE
        ? text
        : text.substring(0, ScheduledTaskEdit.MAX_TITLE) + "…";
  }

  /**
   * {@code ScheduledTask} declares no id generation strategy on either backend, so every task has
   * to arrive with one: JPA rejects a null identifier outright, and anything downstream that keys a
   * task by its id (see {@code ScheduledTaskService#schedule}) would otherwise fail on a null key.
   */
  private static String newTaskId() {
    return UUID.randomUUID().toString().replace("-", "");
  }
}
