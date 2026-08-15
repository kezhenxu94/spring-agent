package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A card refuses any write whose sequence did not strictly increase, and more than one thing writes
 * to a card: the run streaming its answer, and whatever closes a question form on it. Counting
 * separately is what left a card half-written when a user answered before the run had finished.
 */
class FeishuCardSequencesTest {

  private final FeishuCardSequences sequences = new FeishuCardSequences();

  private static Integer get(final Future<Integer> future) {
    try {
      return future.get();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  @DisplayName("both writers on one card draw from the same rising count")
  void oneCountPerCard() {
    assertThat(sequences.next("card-1"))
        .isLessThan(sequences.next("card-1"))
        .isLessThan(sequences.next("card-1"));
  }

  @Test
  @DisplayName("a card nobody has written to yet starts above any count a run could have reached")
  void seededFromTheClock() {
    // What the closer used to rely on outright, and still does when the run whose form it is
    // closing streams on another replica.
    assertThat(sequences.next("card-1")).isCloseTo((int) Instant.now().getEpochSecond(), offset(5));
  }

  @Test
  @DisplayName("cards do not share a count")
  void countsAreNotShared() {
    sequences.next("card-1");
    sequences.next("card-1");

    assertThat(sequences.next("card-2")).isLessThan(sequences.next("card-1"));
  }

  @Test
  @DisplayName("a resync lands above everything handed out, and is not handed out twice")
  void resyncOvertakes() {
    final var handedOut = sequences.next("card-1");

    final var resynced = sequences.resync("card-1");

    assertThat(resynced).isGreaterThan(handedOut);
    assertThat(sequences.next("card-1")).isGreaterThan(resynced);
  }

  @Test
  @DisplayName("concurrent writers never receive the same sequence")
  void neverHandsOutTheSameTwice() throws Exception {
    final Callable<Integer> draw = () -> sequences.next("card-1");
    try (var pool = Executors.newFixedThreadPool(8)) {
      final var handedOut =
          pool.invokeAll(Collections.nCopies(200, draw)).stream()
              .map(FeishuCardSequencesTest::get)
              .toList();

      assertThat(handedOut).hasSize(200).doesNotHaveDuplicates();
    }
  }
}
