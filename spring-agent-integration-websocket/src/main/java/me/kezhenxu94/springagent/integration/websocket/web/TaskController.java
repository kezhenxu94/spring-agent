package me.kezhenxu94.springagent.integration.websocket.web;

import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskEdit;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The work the agent arranged to do later.
 *
 * <p>Read straight off {@code ScheduledTaskRepo} rather than from a run journal, because a
 * scheduled task is not an event in a run: the tools that create one report a sentence to the model
 * and nothing to a surface. The row is the only account of it there is, and it outlives every run
 * involved — the one that created it and each one that fires from it.
 *
 * <p>A task's whole definition can be edited here — what it does, when, until when, how many times
 * and whether anybody is expected to be there — and none of the rules about what those may be live
 * in this class. They are {@code ScheduledTaskEdit}'s, which is also what the agent's {@code
 * UpdateScheduledTask} goes through, so the cron floor and the text cap hold whichever route
 * somebody took. This controller's own job is smaller than it looks: work out who is asking, refuse
 * a task that is not theirs, and turn a JSON body into that edit.
 *
 * <p>What cannot be changed is who owns the task and which conversation its firings write into —
 * that is a different task rather than an edit — nor how many times it has run and when it next
 * will, which belong to the sweeper and are the only account of what has actually happened.
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

  private final ScheduledTaskRepo tasks;
  private final ScheduledTaskService schedules;

  @GetMapping
  public List<Map<String, Object>> list(@AuthenticationPrincipal final OAuth2User principal) {
    final var user = ChatController.user(principal);
    final var out = new ArrayList<Map<String, Object>>();
    for (final var task : tasks.findByUserIdAndStatus(user.id(), ScheduledTask.Status.ACTIVE)) {
      out.add(item(task));
    }
    return out;
  }

  private static Map<String, Object> item(final ScheduledTask task) {
    final var item = new LinkedHashMap<String, Object>();
    item.put("id", task.id());
    // Null for a task written before titles existed, which the page shows by falling back to the
    // prompt rather than by drawing a nameless row.
    item.put("title", task.title());
    item.put("text", task.taskText());
    item.put("cron", task.cronExpression());
    // Null rather than the string "null", which is what String.valueOf makes of an absent Instant
    // and which the page would then try to read as a date.
    item.put("scheduledAt", task.scheduledAt() == null ? null : task.scheduledAt().toString());
    item.put("nextFireAt", task.nextFireAt() == null ? null : task.nextFireAt().toString());
    item.put("expiresAt", task.expiresAt() == null ? null : task.expiresAt().toString());
    item.put("runCount", task.runCount());
    item.put("maxRuns", task.maxRuns());
    item.put("background", Boolean.TRUE.equals(task.background()));
    // Which conversation its firings will appear in, so the UI can link to it rather than leave
    // the user hunting for where the work will show up.
    item.put("conversationId", task.rootMessageId());
    return item;
  }

  /**
   * Changes a task's definition, in whole or in part.
   *
   * <p>A key that is absent leaves that part of the task alone, which is what makes the page able
   * to send only what a person actually touched. Present-but-null is a value rather than an
   * omission for the two fields where absence means something — an expiry and a firing count — and
   * says the task no longer has one.
   *
   * <p>Everything after that, including which writes are partial and whether the next occurrence
   * has to be worked out again, is {@code ScheduledTaskService#edit}'s.
   */
  @PatchMapping("/{id}")
  public Map<String, Object> edit(
      @AuthenticationPrincipal final OAuth2User principal,
      @PathVariable final String id,
      @RequestBody final Map<String, Object> body) {
    final var user = ChatController.user(principal);
    final var task = mine(user.id(), id);
    if (task.status() != ScheduledTask.Status.ACTIVE) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This task is " + task.status() + " and can no longer be changed.");
    }
    final var edit =
        new ScheduledTaskEdit(
            text(body, "title"),
            text(body, "text"),
            text(body, "cron"),
            text(body, "scheduledAt"),
            // Null is the request, not a missing field: the page sends it when somebody clears the
            // expiry, and the edit spells that as the word rather than as an absence it could not
            // tell from "leave it".
            body.containsKey("expiresAt") && body.get("expiresAt") == null
                ? ScheduledTaskEdit.NEVER
                : text(body, "expiresAt"),
            flag(body, "background"),
            body.containsKey("maxRuns") && body.get("maxRuns") == null
                ? ScheduledTaskEdit.UNLIMITED
                : count(body, "maxRuns"));

    final ScheduledTaskEdit.Result result;
    try {
      result = schedules.edit(task, edit);
    } catch (IllegalArgumentException e) {
      // The same message the model is given, which reads as a sentence to a person too — the rules
      // are about what a schedule may be, not about how it was asked for.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
    log.info("Scheduled task {} edited by {}: {}", id, user.id(), result.changes());
    return item(result.task());
  }

  /**
   * A string field, or null where the body did not name it.
   *
   * <p>Blank counts as absent for every field but the two a task is read by, whose own emptiness is
   * refused — a task with no name, or with nothing to do. Everywhere else a cleared input arrives
   * as "" and clearing the schedule box is not a request to run on no schedule at all.
   */
  private static String text(final Map<String, Object> body, final String key) {
    final var value = body.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof String string)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be text.");
    }
    return "text".equals(key) || "title".equals(key) ? string : Strings.emptyToNull(string.trim());
  }

  private static Boolean flag(final Map<String, Object> body, final String key) {
    final var value = body.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof Boolean bool)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be true or false.");
    }
    return bool;
  }

  private static Integer count(final Map<String, Object> body, final String key) {
    final var value = body.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof Number number)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be a number.");
    }
    return number.intValue();
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> cancel(
      @AuthenticationPrincipal final OAuth2User principal, @PathVariable final String id) {
    final var user = ChatController.user(principal);
    final var task = mine(user.id(), id);
    tasks.updateStatus(task.id(), ScheduledTask.Status.CANCELLED);
    log.info("Scheduled task {} cancelled by {}", id, user.id());
    return ResponseEntity.noContent().build();
  }

  /**
   * The task under that id, provided it belongs to the caller.
   *
   * <p>Not found rather than forbidden where it belongs to somebody else: a 403 would confirm that
   * the id names a real task, which is a fact about another person's schedule.
   */
  private ScheduledTask mine(final String userId, final String id) {
    return tasks
        .findById(id)
        .filter(it -> userId.equals(it.userId()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
