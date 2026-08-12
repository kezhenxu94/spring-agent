package me.kezhenxu94.springagent.core.tools;

import com.google.common.base.Strings;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Slf4j
@AgentTool(scenario = AgentScenario.CHAT)
@Component
@RequiredArgsConstructor
public class ScheduledTaskTool {

  final ScheduledTaskRepo scheduledTaskRepo;
  final ScheduledTaskService scheduledTaskService;

  @Tool(
      name = "CreateScheduledTask",
      description =
"""
创建定时任务。循环任务请提供 6 字段 Spring cron 表达式（秒 分 时 日 月 周）。
示例："0 0 1 * * MON" = 每周一 09:00（北京时间），"0 */30 * * * *" = 每 30 分钟，
"0 0 0 * * MON-FRI" = 工作日 08:00（北京时间）。
一次性任务请提供 scheduledAt，格式为 ISO-8601（北京时间，如 "2025-01-15T10:00:00+08:00"）。
相对时间（如"明天上午 10 点"）请先调用 DateTimeTool 转换后再调用本工具。
cronExpression 与 scheduledAt 只能提供一个。
""")
  public String createScheduledTask(
      @ToolParam(description = "任务触发时要发送给 AI 的文本内容") final String taskText,
      @ToolParam(description = "循环任务的 6 字段 Spring cron 表达式，一次性任务传 null", required = false)
          final String cronExpression,
      @ToolParam(
              description =
                  "一次性任务的触发时间，ISO-8601 格式（北京时间，如 \"2025-01-15T10:00:00+08:00\"），循环任务传 null",
              required = false)
          final String scheduledAt,
      @ToolParam(
              description =
                  "任务过期时间，ISO-8601 格式（北京时间，如 \"2025-12-31T23:59:59+08:00\"）。传入 \"never\""
                      + " 表示永不过期。不填则默认 7 天后过期。",
              required = false)
          final String expiresAt,
      final ToolContext context) {

    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    final var rootMessageId = ToolContexts.require(context, ToolContexts.ROOT_MESSAGE_ID);
    final var chatId = ToolContexts.get(context, ToolContexts.CHAT_ID);
    final var chatType = ToolContexts.get(context, ToolContexts.CHAT_TYPE);

    final var hasCron = !Strings.isNullOrEmpty(cronExpression);
    final var hasScheduledAt = !Strings.isNullOrEmpty(scheduledAt);

    if (hasCron && hasScheduledAt) {
      return "错误：cronExpression 与 scheduledAt 只能提供一个。";
    }
    if (!hasCron && !hasScheduledAt) {
      return "错误：循环任务请提供 cronExpression，一次性任务请提供 scheduledAt。";
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
        return "错误：expiresAt 格式无效，请使用 ISO-8601 格式（如 2025-12-31T23:59:59+08:00）或 \"never\"。";
      }
      if (parsed.isBefore(Instant.now())) {
        return "错误：expiresAt 必须是未来的时间。";
      }
      resolvedExpiresAt = parsed;
    }

    final var expiryNote =
        resolvedExpiresAt == null ? "永久有效（不过期）。" : "过期时间：" + resolvedExpiresAt + "。";

    if (hasCron) {
      final var validated = validateAndNormalizeCron(cronExpression);
      if (validated.startsWith("Error:")) {
        return validated;
      }
      final var task =
          scheduledTaskRepo.save(
              ScheduledTask.builder()
                  .userId(userId)
                  .chatId(chatId)
                  .chatType(chatType)
                  .rootMessageId(rootMessageId)
                  .taskText(taskText)
                  .cronExpression(validated)
                  .expiresAt(resolvedExpiresAt)
                  .status(ScheduledTask.Status.ACTIVE)
                  .build());
      scheduledTaskService.schedule(task);
      final var overrideNote =
          validated.equals(cronExpression) ? "" : "（注意：触发间隔已调整为最小允许值 " + validated + "）";
      return "已创建循环任务："
          + taskText
          + "（"
          + validated
          + "），任务 ID "
          + task.getId()
          + "。"
          + expiryNote
          + overrideNote
          + "如需提前取消，请使用 cancelScheduledTask 并提供该 ID。";
    } else {
      final Instant fireAt;
      try {
        fireAt = Instant.parse(scheduledAt);
      } catch (Exception e) {
        return "错误：scheduledAt 格式无效，请使用 ISO-8601 格式（如 2025-01-15T10:00:00+08:00）。";
      }
      if (fireAt.isBefore(Instant.now())) {
        return "错误：scheduledAt 必须是未来的时间。";
      }
      final var task =
          scheduledTaskRepo.save(
              ScheduledTask.builder()
                  .userId(userId)
                  .chatId(chatId)
                  .chatType(chatType)
                  .rootMessageId(rootMessageId)
                  .taskText(taskText)
                  .scheduledAt(fireAt)
                  .expiresAt(resolvedExpiresAt)
                  .status(ScheduledTask.Status.ACTIVE)
                  .build());
      scheduledTaskService.schedule(task);
      return "已创建一次性任务："
          + taskText
          + "，触发时间 "
          + fireAt
          + "，任务 ID "
          + task.getId()
          + "。"
          + expiryNote
          + "如需提前取消，请使用 cancelScheduledTask 并提供该 ID。";
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
      return "暂无活跃的定时任务。";
    }
    return tasks.stream()
        .map(
            t ->
                "- id="
                    + t.getId()
                    + " | task="
                    + t.getTaskText()
                    + " | schedule="
                    + (t.getCronExpression() != null ? t.getCronExpression() : t.getScheduledAt()))
        .collect(Collectors.joining("\n"));
  }

  @Tool(name = "CancelScheduledTask", description = "Cancel a scheduled task by ID")
  public String cancelScheduledTask(
      @ToolParam(description = "The task ID to cancel") final String taskId,
      final ToolContext context) {
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    final var taskOpt = scheduledTaskRepo.findById(taskId);
    if (taskOpt.isEmpty()) {
      return "错误：未找到 ID 为 " + taskId + " 的任务。";
    }
    final var task = taskOpt.get();
    if (!task.getUserId().equals(userId)) {
      return "错误：只能取消自己创建的任务。";
    }
    if (task.getStatus() != ScheduledTask.Status.ACTIVE) {
      return "任务 " + taskId + " 已处于 " + task.getStatus() + " 状态。";
    }
    scheduledTaskRepo.save(task.toBuilder().status(ScheduledTask.Status.CANCELLED).build());
    scheduledTaskService.unschedule(taskId);
    return "已取消任务 " + taskId;
  }

  private String validateAndNormalizeCron(final String expr) {
    try {
      CronExpression.parse(expr);
    } catch (Exception e) {
      return "错误：cron 表达式 '" + expr + "' 无效：" + e.getMessage();
    }
    return enforceMinimumInterval(expr);
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
