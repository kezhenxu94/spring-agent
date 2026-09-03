package me.kezhenxu94.springagent.integration.websocket.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
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
 * <p>What a task will do can be edited here; when it will do it cannot. Creating a task and
 * rescheduling one belong to the agent, which has tools for both, and a second way to set a
 * schedule in the UI would be a second set of rules about what a schedule may be. The prompt has no
 * such rules — it is prose — and correcting it is otherwise a matter of cancelling the task and
 * asking for it again, which loses the conversation its firings write into.
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

  /** The length {@code ScheduledTask#taskText} is declared at, which is what actually stores it. */
  private static final int MAX_TASK_TEXT = 8192;

  private final ScheduledTaskRepo tasks;

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
    item.put("text", task.taskText());
    item.put("cron", task.cronExpression());
    // Null rather than the string "null", which is what String.valueOf makes of an absent Instant
    // and which the page would then try to read as a date.
    item.put("scheduledAt", task.scheduledAt() == null ? null : task.scheduledAt().toString());
    item.put("nextFireAt", task.nextFireAt() == null ? null : task.nextFireAt().toString());
    item.put("runCount", task.runCount());
    item.put("maxRuns", task.maxRuns());
    item.put("background", Boolean.TRUE.equals(task.background()));
    // Which conversation its firings will appear in, so the UI can link to it rather than leave
    // the user hunting for where the work will show up.
    item.put("conversationId", task.rootMessageId());
    return item;
  }

  /**
   * Rewrites what a task will do.
   *
   * <p>The write is partial — see {@code ScheduledTaskRepo#updateTaskText} — because the sweeper is
   * writing {@code runCount} and {@code nextFireAt} on this same row from another thread, or
   * another replica, while this edit is in flight.
   *
   * <p>The cap is the column's: {@code taskText} is declared 8192 characters, and a longer prompt
   * would be a truncation on JPA and a silent difference between backends everywhere else.
   */
  @PatchMapping("/{id}")
  public Map<String, Object> edit(
      @AuthenticationPrincipal final OAuth2User principal,
      @PathVariable final String id,
      @RequestBody final Map<String, Object> body) {
    final var user = ChatController.user(principal);
    final var task = mine(user.id(), id);
    final var text = String.valueOf(body.getOrDefault("text", "")).trim();
    if (text.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A task needs something to do.");
    }
    if (text.length() > MAX_TASK_TEXT) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "A task's text is limited to " + MAX_TASK_TEXT + " characters.");
    }
    tasks.updateTaskText(task.id(), text);
    log.info("Scheduled task {} edited by {}", id, user.id());
    return item(task.toBuilder().taskText(text).build());
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
