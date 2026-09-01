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
import org.springframework.web.bind.annotation.PathVariable;
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
 * <p>Read-only apart from cancelling. Creating and rescheduling belong to the agent, which has
 * tools for both; a second way to do it in the UI would be a second set of rules about what a
 * schedule may be.
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

  private final ScheduledTaskRepo tasks;

  @GetMapping
  public List<Map<String, Object>> list(@AuthenticationPrincipal final OAuth2User principal) {
    final var user = ChatController.user(principal);
    final var out = new ArrayList<Map<String, Object>>();
    for (final var task : tasks.findByUserIdAndStatus(user.id(), ScheduledTask.Status.ACTIVE)) {
      final var item = new LinkedHashMap<String, Object>();
      item.put("id", task.id());
      item.put("text", task.taskText());
      item.put("cron", task.cronExpression());
      item.put("scheduledAt", String.valueOf(task.scheduledAt()));
      item.put("runCount", task.runCount());
      item.put("maxRuns", task.maxRuns());
      item.put("background", Boolean.TRUE.equals(task.background()));
      // Which conversation its firings will appear in, so the UI can link to it rather than leave
      // the user hunting for where the work will show up.
      item.put("conversationId", task.rootMessageId());
      out.add(item);
    }
    return out;
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> cancel(
      @AuthenticationPrincipal final OAuth2User principal, @PathVariable final String id) {
    final var user = ChatController.user(principal);
    final var task =
        tasks
            .findById(id)
            .filter(it -> user.id().equals(it.userId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    tasks.updateStatus(task.id(), ScheduledTask.Status.CANCELLED);
    log.info("Scheduled task {} cancelled by {}", id, user.id());
    return ResponseEntity.noContent().build();
  }
}
