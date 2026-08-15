package me.kezhenxu94.springagent.integration.feishu.handler;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

/**
 * The one place a card's sequence numbers come from.
 *
 * <p>A card rejects an operation whose sequence did not strictly increase, so everything writing to
 * one card has to count from the same place. More than one thing does: the run's {@link
 * FeishuCardUpdater} writes the answer as it streams, and {@link FeishuQuestionFormCloser} closes a
 * form when it is answered or overtaken. Those two used to count separately — the updater from two,
 * the closer from the clock — which held only while the closer ran after the run. It does not: a
 * user who answers while the run is still streaming left every write the run had left rejected, the
 * stop button on the card and streaming mode never turned off.
 *
 * <p>Seeded from the clock rather than from zero so that a card first written to here — the run
 * gone, or answered on another replica — still lands above whatever a run counted to.
 */
@Component
public class FeishuCardSequences {

  /**
   * Cards are finite but a long-lived server would hold every one it ever wrote to. Dropping an
   * idle one is safe because the next sequence is seeded from the clock, which has moved on: a
   * re-seeded counter only lands below where it was if the card took more writes than the seconds
   * it has been idle, which an hour makes fanciful.
   */
  private final Cache<String, AtomicInteger> byCardId =
      CacheBuilder.newBuilder().expireAfterAccess(Duration.ofHours(1)).maximumSize(10_000).build();

  /** The next sequence for {@code cardId}, higher than every one handed out for it before. */
  @SneakyThrows
  public int next(final String cardId) {
    return byCardId
        .get(cardId, () -> new AtomicInteger((int) Instant.now().getEpochSecond()))
        .getAndIncrement();
  }

  /**
   * Moves the card past a sequence the server has already seen, for a caller the server has just
   * refused. Returns the next sequence to try.
   *
   * <p>Needed because two replicas cannot share the counter above: the run streams on the one that
   * took the message and the answer arrives on whichever took the callback, so one of them can be
   * behind. What it is behind by is unknowable — the server does not say what it last accepted — so
   * the clock is the one guess both sides make the same way.
   */
  @SneakyThrows
  public int resync(final String cardId) {
    final var counter =
        byCardId.get(cardId, () -> new AtomicInteger((int) Instant.now().getEpochSecond()));
    final var now = (int) Instant.now().getEpochSecond();
    // Raise the floor, then hand out from it as any other caller would, so the counter is left
    // above what this returns rather than pointing at it.
    counter.updateAndGet(current -> Math.max(current, now + 1));
    return counter.getAndIncrement();
  }
}
