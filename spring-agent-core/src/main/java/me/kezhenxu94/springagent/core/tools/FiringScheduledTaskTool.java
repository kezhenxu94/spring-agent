package me.kezhenxu94.springagent.core.tools;

import com.google.common.base.Strings;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * What a firing scheduled task may do to itself, as against {@link ScheduledTaskTool}, which a
 * scheduled run is not offered at all. The split is the whole point: these two tools name no task,
 * they act on the one that is firing, so a run can end a repeating task or arrange the next
 * follow-up without ever being able to add a task to the pile. However many times a firing calls
 * them, the number of scheduled tasks is what it was.
 *
 * <p>Which task that is comes from {@code ToolContexts.REQUEST_ID}: {@code
 * ScheduledTaskService#fire} builds the request with the task's own id as its request id, the same
 * invariant {@code unschedule} relies on when it cancels a firing by task id. A run that is not a
 * firing therefore resolves to no task, and both tools refuse — which is what makes them harmless
 * in a scenario that offers them for want of knowing better.
 */
@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class FiringScheduledTaskTool {

  final ScheduledTaskRepo scheduledTaskRepo;
  final ScheduledTaskService scheduledTaskService;
  final CoreMessages messages;

  @Tool(
      name = "StopThisScheduledTask",
      description =
"""
Stop the scheduled task that is running right now, so it never fires again. This is how a task \
that repeats "until X happens" ends: check whether it has happened, and call this when it has. \
The task is left as completed, and what you say in this run is still delivered as usual.
Only this task is affected — use ListScheduledTasks and CancelScheduledTask, in a conversation \
with the user, to stop any other.
""")
  public String stopThisScheduledTask(
      @ToolParam(
              description = "Why the task is being stopped, in one line, for the log and the user")
          final String reason,
      final ToolContext context) {
    final var task = firingTask(context);
    if (task == null) {
      return messages.get("firing-task-none");
    }
    scheduledTaskService.stopFiringTask(task.id());
    return messages.get("firing-task-stopped", task.id(), Strings.nullToEmpty(reason));
  }

  @Tool(
      name = "RescheduleThisScheduledTask",
      description =
"""
Give the one-off task that is running right now a next firing, instead of asking for a new task. \
This is how "remind me in 30 minutes, and if it is still not done remind me again 50 minutes \
later" works: the same task fires again at the time you give, optionally saying something else.
Resolve anything relative with CurrentDateTime first, since the time here is absolute. Do nothing \
if there is to be no next firing — say so in your answer and the task ends by itself.
A task that already repeats on a schedule cannot be moved this way: stop it with \
StopThisScheduledTask instead.
""")
  public String rescheduleThisScheduledTask(
      @ToolParam(
              description =
                  "When this task fires next, ISO-8601 with an offset (for example"
                      + " \"2025-01-15T10:00:00+08:00\")")
          final String nextRunAt,
      @ToolParam(
              description =
                  "What the task says when it fires next; null to fire with the same text again",
              required = false)
          final String taskText,
      final ToolContext context) {
    final var task = firingTask(context);
    if (task == null) {
      return messages.get("firing-task-none");
    }
    if (!Strings.isNullOrEmpty(task.cronExpression())) {
      return messages.get("firing-task-repeats", task.cronExpression());
    }
    final Instant fireAt;
    try {
      fireAt = Instant.parse(nextRunAt);
    } catch (Exception e) {
      return messages.get("firing-task-bad-next-run");
    }
    if (fireAt.isBefore(Instant.now())) {
      return messages.get("firing-task-next-run-past");
    }
    if (task.expiresAt() != null && fireAt.isAfter(task.expiresAt())) {
      return messages.get("firing-task-after-expiry", task.expiresAt(), fireAt);
    }
    final var updated = task.toBuilder().scheduledAt(fireAt);
    if (!Strings.isNullOrEmpty(taskText)) {
      updated.taskText(taskText);
    }
    final var saved = scheduledTaskRepo.save(updated.build());
    scheduledTaskService.rearmFiringTask(saved);
    return messages.get("firing-task-rearmed", saved.id(), fireAt, saved.taskText());
  }

  /**
   * The task this run is a firing of, or null when the run is not one. Only an active task is
   * returned: one already stopped, expired or completed has nothing left to change.
   */
  private ScheduledTask firingTask(final ToolContext context) {
    final var requestId = ToolContexts.get(context, ToolContexts.REQUEST_ID);
    if (Strings.isNullOrEmpty(requestId)) {
      return null;
    }
    return scheduledTaskRepo
        .findById(requestId)
        .filter(task -> task.status() == ScheduledTask.Status.ACTIVE)
        .orElse(null);
  }
}
