package me.kezhenxu94.springagent.integration.websocket.run;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The journals of the runs this process is currently able to replay.
 *
 * <p>Keyed by request id, and looked up by conversation as well: a browser coming back after a
 * refresh knows which conversation it was in, and not which run is going in it.
 *
 * <p>This is memory, so it is bounded twice over. A finished journal is kept for {@code
 * app.web.journal.retention} rather than dropped at once, because the common case is a user
 * reloading the page seconds after an answer arrived and expecting to still see how it was reached.
 * Beyond that, {@code app.web.journal.max-runs} caps how many are held at all, evicting finished
 * ones first and oldest first — a live run is never evicted, since dropping it would strand a
 * browser that has nowhere else to read the run from.
 *
 * <p>What falls out is not lost, it is only coarser: the conversation itself is in chat memory and
 * an unanswered question is in {@code PendingQuestion}, both of which outlive this process
 * entirely. What eviction costs is the fine grain — which tools ran, what the subagents did.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunJournals {

  private final WebProperties properties;

  private final ConcurrentMap<String, RunJournal> byRequestId = new ConcurrentHashMap<>();

  public RunJournal open(final String requestId, final String conversationId, final String userId) {
    final var journal = new RunJournal(requestId, conversationId, userId);
    byRequestId.put(requestId, journal);
    evictIfOverCapacity();
    return journal;
  }

  public Optional<RunJournal> byRequestId(final String requestId) {
    return Optional.ofNullable(requestId == null ? null : byRequestId.get(requestId));
  }

  /**
   * The run going in {@code conversationId} right now, if there is one.
   *
   * <p>Only a live one: a finished journal is still here for replay, but a browser asking "what is
   * happening in this conversation" is asking what to attach to, and attaching to a run that has
   * ended would leave it waiting for a stream that is already over.
   */
  public Optional<RunJournal> liveByConversationId(final String conversationId) {
    if (conversationId == null) {
      return Optional.empty();
    }
    return byRequestId.values().stream()
        .filter(RunJournal::live)
        .filter(it -> conversationId.equals(it.conversationId()))
        .findFirst();
  }

  /** Swept rather than scheduled per journal: one timer is cheaper than one task per run. */
  @Scheduled(fixedDelayString = "${app.web.journal.sweep-interval:PT1M}")
  public void evictFinished() {
    final var deadline = Instant.now().minus(properties.journal().retention());
    byRequestId.values().stream()
        .filter(it -> !it.live())
        .filter(it -> it.finishedAt() != null && it.finishedAt().isBefore(deadline))
        .toList()
        .forEach(this::evict);
    evictIfOverCapacity();
  }

  private void evictIfOverCapacity() {
    final var max = properties.journal().maxRuns();
    if (byRequestId.size() <= max) {
      return;
    }
    // Finished first, oldest first. A live run is never a candidate: the browser reading it has no
    // other source for what it is saying, and this cache is not worth stranding a run over.
    final List<RunJournal> candidates =
        byRequestId.values().stream()
            .filter(it -> !it.live())
            .sorted(
                Comparator.comparing(
                    RunJournal::finishedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
            .toList();
    var over = byRequestId.size() - max;
    for (final var journal : candidates) {
      if (over-- <= 0) {
        return;
      }
      evict(journal);
    }
    if (byRequestId.size() > max) {
      // Said out loud rather than enforced: the alternative is dropping a live run, and a log line
      // about a cap that live traffic is holding open is more use than a stranded browser.
      log.warn("{} run journals held, over the {} cap, all of them live", byRequestId.size(), max);
    }
  }

  private void evict(final RunJournal journal) {
    byRequestId.remove(journal.requestId());
    journal.closeReaders();
    log.debug("Evicted the journal of run {}", journal.requestId());
  }
}
