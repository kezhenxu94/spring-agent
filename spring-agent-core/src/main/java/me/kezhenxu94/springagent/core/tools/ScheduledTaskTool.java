package me.kezhenxu94.springagent.core.tools;

import com.google.common.base.Strings;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class ScheduledTaskTool implements ScenarioGatedTool {

  final ScheduledTaskRepo scheduledTaskRepo;
  final ScheduledTaskService scheduledTaskService;

  @Override
  public boolean appliesTo(final AgentScenario scenario) {
    // Anything but a task the agent scheduled for itself: a run that fires on a schedule must not
    // be able to schedule more of itself, which is how one task becomes a growing pile of them.
    return scenario != BuiltInScenarios.SCHEDULED_TASK;
  }

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
""")
  public String createScheduledTask(
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
      final ToolContext context) {

    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    final var rootMessageId = ToolContexts.require(context, ToolContexts.ROOT_MESSAGE_ID);
    final var chatId = ToolContexts.get(context, ToolContexts.CHAT_ID);
    final var chatType = ToolContexts.get(context, ToolContexts.CHAT_TYPE);

    final var hasCron = !Strings.isNullOrEmpty(cronExpression);
    final var hasScheduledAt = !Strings.isNullOrEmpty(scheduledAt);

    if (hasCron && hasScheduledAt) {
      return "Error: give either cronExpression or scheduledAt, not both.";
    }
    if (!hasCron && !hasScheduledAt) {
      return "Error: give cronExpression for a recurring task, or scheduledAt for a one-off.";
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
        return "Error: expiresAt must be ISO-8601 with an offset (for example"
            + " 2025-12-31T23:59:59+08:00), or \"never\".";
      }
      if (parsed.isBefore(Instant.now())) {
        return "Error: expiresAt must be in the future.";
      }
      resolvedExpiresAt = parsed;
    }

    final var expiryNote =
        resolvedExpiresAt == null
            ? "It never expires."
            : "It expires at " + resolvedExpiresAt + ".";

    final var backgroundNote =
        Boolean.TRUE.equals(background)
            ? " It runs in the background: nothing is posted when it fires, so anything you should"
                + " see it has to send itself, and only a failure is reported."
            : "";

    if (hasCron) {
      try {
        CronExpression.parse(cronExpression);
      } catch (Exception e) {
        return "Error: cron expression '" + cronExpression + "' is invalid: " + e.getMessage();
      }
      final var validated = enforceMinimumInterval(cronExpression);
      final var task =
          scheduledTaskRepo.save(
              ScheduledTask.builder()
                  .id(newTaskId())
                  .userId(userId)
                  .chatId(chatId)
                  .chatType(chatType)
                  .rootMessageId(rootMessageId)
                  .taskText(taskText)
                  .cronExpression(validated)
                  .expiresAt(resolvedExpiresAt)
                  .background(background)
                  .status(ScheduledTask.Status.ACTIVE)
                  .build());
      scheduledTaskService.schedule(task);
      final var overrideNote =
          validated.equals(cronExpression)
              ? ""
              : " The interval was raised to the smallest one allowed, " + validated + ".";
      return "Created the recurring task \""
          + taskText
          + "\" ("
          + validated
          + "), id "
          + task.id()
          + ". "
          + expiryNote
          + overrideNote
          + backgroundNote
          + " Cancel it early with CancelScheduledTask and that id.";
    } else {
      final Instant fireAt;
      try {
        fireAt = Instant.parse(scheduledAt);
      } catch (Exception e) {
        return "Error: scheduledAt must be ISO-8601 with an offset (for example"
            + " 2025-01-15T10:00:00+08:00).";
      }
      if (fireAt.isBefore(Instant.now())) {
        return "Error: scheduledAt must be in the future.";
      }
      final var task =
          scheduledTaskRepo.save(
              ScheduledTask.builder()
                  .id(newTaskId())
                  .userId(userId)
                  .chatId(chatId)
                  .chatType(chatType)
                  .rootMessageId(rootMessageId)
                  .taskText(taskText)
                  .scheduledAt(fireAt)
                  .expiresAt(resolvedExpiresAt)
                  .background(background)
                  .status(ScheduledTask.Status.ACTIVE)
                  .build());
      scheduledTaskService.schedule(task);
      return "Created the one-off task \""
          + taskText
          + "\", firing at "
          + fireAt
          + ", id "
          + task.id()
          + ". "
          + expiryNote
          + backgroundNote
          + " Cancel it early with CancelScheduledTask and that id.";
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
      return "You have no active scheduled tasks.";
    }
    return tasks.stream()
        .map(
            t ->
                "- id="
                    + t.id()
                    + " | task="
                    + t.taskText()
                    + " | schedule="
                    + (t.cronExpression() != null ? t.cronExpression() : t.scheduledAt())
                    + (Boolean.TRUE.equals(t.background()) ? " | background" : ""))
        .collect(Collectors.joining("\n"));
  }

  @Tool(name = "CancelScheduledTask", description = "Cancel a scheduled task by ID")
  public String cancelScheduledTask(
      @ToolParam(description = "The task ID to cancel") final String taskId,
      final ToolContext context) {
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    final var taskOpt = scheduledTaskRepo.findById(taskId);
    if (taskOpt.isEmpty()) {
      return "Error: no task with id " + taskId + ".";
    }
    final var task = taskOpt.get();
    if (!task.userId().equals(userId)) {
      return "Error: you can only cancel tasks you created yourself.";
    }
    if (task.status() != ScheduledTask.Status.ACTIVE) {
      return "Task " + taskId + " is already " + task.status() + ".";
    }
    scheduledTaskRepo.save(task.toBuilder().status(ScheduledTask.Status.CANCELLED).build());
    scheduledTaskService.unschedule(taskId);
    return "Cancelled task " + taskId + ".";
  }

  /**
   * {@code ScheduledTask} declares no id generation strategy on either backend, so every task has
   * to arrive with one: JPA rejects a null identifier outright, and anything downstream that keys a
   * task by its id (see {@code ScheduledTaskService#schedule}) would otherwise fail on a null key.
   */
  private static String newTaskId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private String enforceMinimumInterval(final String expr) {
    final var parts = expr.trim().split("\\s+");
    if (parts.length != 6) {
      return expr;
    }
    final var seconds = parts[0];
    final var minutes = parts[1];

    // Reject sub-minute intervals on seconds field (e.g. */10, */30)
    if (seconds.startsWith("*/")) {
      parts[0] = "0";
    }

    // Enforce minimum 5-minute interval on minutes field
    if (parts[1].startsWith("*/")) {
      try {
        final var n = Integer.parseInt(parts[1].substring(2));
        if (n < 5) {
          parts[1] = "*/5";
          log.info("Cron '{}' interval too frequent, adjusted minutes field to */5", expr);
        }
      } catch (NumberFormatException ignored) {
      }
    }

    // If seconds was sub-minute but minutes is 0, treat as every-minute — enforce */5 minutes
    if (seconds.startsWith("*/") && parts[1].equals("*")) {
      parts[1] = "*/5";
      log.info("Cron '{}' had sub-minute seconds with wildcard minutes, adjusted to */5", expr);
    }

    return String.join(" ", parts);
  }
}
