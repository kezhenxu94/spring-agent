package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.DeleteCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.DeleteCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReq;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardResp;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementResp;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.kezhenxu94.springagent.core.tools.UserHome;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A card as a run actually gets one: every call it makes is made by a worker of its own, so that a
 * run streaming into a card never waits out a round trip to Feishu.
 *
 * <p>Which is the point at which the sequence becomes the thing worth testing. The number a card
 * refuses out of order used to be drawn under a lock held across the call, so ordering came for
 * free at the cost of the run's thread; it is now drawn by whichever worker is draining the queue,
 * and what has to hold is that only ever one is — see {@code FeishuCard}.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardAsyncWriteTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @TempDir Path userHomeRoot;

  /** Every call the card made, as {@code operation:detail}, in the order the SDK saw them. */
  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

  /** Every sequence the card sent, in the order the SDK was actually called. */
  private final List<Integer> sequences = Collections.synchronizedList(new ArrayList<>());

  /** Held before a streaming call returns, for the tests that need one still in flight. */
  private final CountDownLatch releaseStreaming = new CountDownLatch(1);

  /** Counted down as a streaming call is entered, whether or not it is being held. */
  private CountDownLatch streamingEntered = new CountDownLatch(1);

  private boolean holdStreaming;

  private ScheduledExecutorService clock;
  private ExecutorService writes;

  @BeforeEach
  void setUp() throws Exception {
    final var streamed = new ContentCardElementResp();
    streamed.setCode(0);
    lenient()
        .when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenAnswer(
            invocation -> {
              final ContentCardElementReq request = invocation.getArgument(0);
              final var body = request.getContentCardElementReqBody();
              calls.add("stream:" + Thread.currentThread().getName() + ":" + body.getContent());
              sequences.add(body.getSequence());
              streamingEntered.countDown();
              if (holdStreaming) {
                releaseStreaming.await(10, TimeUnit.SECONDS);
              }
              return streamed;
            });
    final var deleted = new DeleteCardElementResp();
    deleted.setCode(0);
    lenient()
        .when(feishu.cardkit().v1().cardElement().delete(any(DeleteCardElementReq.class)))
        .thenReturn(deleted);
    final var settings = new SettingsCardResp();
    settings.setCode(0);
    lenient()
        .when(feishu.cardkit().v1().card().settings(any(SettingsCardReq.class)))
        .thenReturn(settings);
    clock = Executors.newScheduledThreadPool(1);
    writes =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("feishu-card-write-", 1).factory());
  }

  @AfterEach
  void tearDown() {
    releaseStreaming.countDown();
    clock.shutdownNow();
    writes.shutdownNow();
  }

  private FeishuCard card(final Duration interval) {
    return new FeishuCard(
        feishu, "card-1", null, new UserHome(userHomeRoot), messages(), interval, 0, clock, writes);
  }

  @Test
  @DisplayName("a run streams into the card without waiting for the call that carries it")
  void streamingDoesNotBlockTheRun() throws Exception {
    holdStreaming = true;
    final var card = card(Duration.ZERO);

    // Returns while the call it queued is still out — which is the whole of the change: this is
    // the thread consuming the model's stream, and it used to be the thread making the call.
    card.stream("message", "The quick brown fox");

    assertThat(streamingEntered.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(releaseStreaming.getCount()).isEqualTo(1);
    assertThat(callers()).containsExactly("feishu-card-write-1");
  }

  @Test
  @DisplayName("a card being written to slowly does not hold up the next thing the run says")
  void aSlowWriteDoesNotHoldUpTheRun() throws Exception {
    holdStreaming = true;
    final var card = card(Duration.ZERO);
    card.stream("message", "The");
    assertThat(streamingEntered.await(10, TimeUnit.SECONDS)).isTrue();

    // The worker is out on the first call, so this one has nowhere to go but the queue — and the
    // run is not the one waiting for it.
    card.stream("message", "The quick brown fox");

    releaseStreaming.countDown();
    finish(card);
    // Coalesced onto the newest, since the run's content is cumulative and only the newest was
    // ever going to be on screen.
    assertThat(contents()).containsExactly("The", "The quick brown fox");
  }

  @Test
  @DisplayName("two writers to one card leave the sequence strictly increasing")
  void oneWorkerAtATimeKeepsTheSequenceInOrder() throws Exception {
    final var card = card(Duration.ZERO);
    final var start = new CountDownLatch(1);
    final var writers =
        List.of(
            writer(start, i -> card.stream("message", "the run, chunk " + i)),
            writer(start, i -> card.stream("subagent-message", "the subagent, chunk " + i)));
    writers.forEach(Thread::start);
    start.countDown();
    for (final var writer : writers) {
      writer.join(TimeUnit.SECONDS.toMillis(10));
    }
    finish(card);

    assertThat(sequences).isSorted();
    // Sorted is not enough: the same number twice would also be sorted, and the card refuses a
    // sequence that did not strictly increase.
    assertThat(sequences).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("what the run last said reaches the card before the run is reported finished")
  void finishingWaitsForWhatIsQueued() {
    final var card = card(Duration.ofSeconds(30));
    card.stream("message", "The");
    card.stream("message", "The quick brown fox");

    // Nothing else is going to carry the newest one out — the interval has 30 seconds to run and
    // the run has stopped talking — so finishing is what has to.
    finish(card);

    // On what was written last rather than on everything written, because how many calls it took
    // is the worker's business: the first chunk goes out at once and the second is either coalesced
    // onto it or follows it, depending on whether the worker had picked it up yet. What cannot
    // differ is that the card ends up showing what the run last said.
    assertThat(contents()).last().isEqualTo("The quick brown fox");
  }

  @Test
  @DisplayName("a pane rebuilt twice inside one interval is sent once, as it last stood")
  void replacementsOfOneElementAreSuperseded() throws Exception {
    final var replaced = new UpdateCardElementResp();
    replaced.setCode(0);
    final var updates = Collections.synchronizedList(new ArrayList<String>());
    lenient()
        .when(feishu.cardkit().v1().cardElement().update(any(UpdateCardElementReq.class)))
        .thenAnswer(
            invocation -> {
              final UpdateCardElementReq request = invocation.getArgument(0);
              updates.add(request.getUpdateCardElementReqBody().getElement());
              return replaced;
            });
    holdStreaming = true;
    final var card = card(Duration.ZERO);
    // Held so that the three replacements below are certainly queued together rather than racing
    // the worker: what is under test is what the queue does with them, not how fast it is drained.
    card.stream("message", "the run is working");
    assertThat(streamingEntered.await(10, TimeUnit.SECONDS)).isTrue();

    card.replace("tools", "{\"one call\":true}", "tools:1");
    card.replace("tools", "{\"two calls\":true}", "tools:2");
    card.replace("tools", "{\"three calls\":true}", "tools:3");

    releaseStreaming.countDown();
    finish(card);

    // A replacement carries the element whole, so the two behind the newest were never going to be
    // seen — a turn making twenty tool calls in a second costs the card one write, not twenty.
    assertThat(updates).containsExactly("{\"three calls\":true}");
  }

  @Test
  @DisplayName("an insert still says whether it landed, and lands after what was queued before it")
  void insertsAreWaitedForAndOrdered() throws Exception {
    final var inserted = new CreateCardElementResp();
    inserted.setCode(0);
    lenient()
        .when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenAnswer(
            invocation -> {
              final CreateCardElementReq request = invocation.getArgument(0);
              final var body = request.getCreateCardElementReqBody();
              calls.add("insert:" + body.getTargetElementId());
              sequences.add(body.getSequence());
              return inserted;
            });
    holdStreaming = true;
    final var card = card(Duration.ZERO);
    card.stream("message", "The");
    assertThat(streamingEntered.await(10, TimeUnit.SECONDS)).isTrue();
    card.stream("message", "The quick brown fox");
    releaseStreaming.countDown();

    // Nothing can be streamed into an element the card does not have, so this is one of the few
    // writes whose caller has to know — and it drains what the run has already said on its way.
    assertThat(card.insertBefore("usage", "[{\"element_id\":\"reasoning\"}]", "reasoning"))
        .isTrue();

    assertThat(operations())
        .containsExactly("stream:The", "stream:The quick brown fox", "insert:usage");
  }

  @Test
  @DisplayName("an insert that failed says so, so its caller can put the element on again")
  void aFailedInsertIsReported() throws Exception {
    final var refused = new CreateCardElementResp();
    refused.setCode(1);
    refused.setMsg("no such element");
    lenient()
        .when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenReturn(refused);
    final var card = card(Duration.ZERO);

    assertThat(card.insertBefore("gone", "[{\"element_id\":\"reasoning\"}]", "reasoning"))
        .isFalse();
  }

  /**
   * Finishing takes the stop button off the card and closes streaming mode; both just succeed.
   *
   * <p>Stubbed in {@code setUp} rather than here, where the calls are: a worker may be mid-call on
   * this card by then, and Mockito is not something to be stubbed and invoked at once.
   */
  private void finish(final FeishuCard card) {
    card.finish();
  }

  private Thread writer(final CountDownLatch start, final java.util.function.IntConsumer write) {
    return new Thread(
        () -> {
          try {
            start.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          for (var i = 0; i < 50; i++) {
            write.accept(i);
          }
        });
  }

  /** What each streaming call carried, in order. */
  private List<String> contents() {
    return calls.stream()
        .filter(call -> call.startsWith("stream:"))
        .map(call -> call.split(":", 3)[2])
        .toList();
  }

  /** Every call as {@code operation:detail}, with the thread that made it left out. */
  private List<String> operations() {
    return calls.stream()
        .map(call -> call.startsWith("stream:") ? "stream:" + call.split(":", 3)[2] : call)
        .toList();
  }

  /** Which threads the streaming calls were made on. */
  private List<String> callers() {
    return calls.stream()
        .filter(call -> call.startsWith("stream:"))
        .map(call -> call.split(":", 3)[1])
        .distinct()
        .toList();
  }

  private FeishuMessages messages() {
    return new FeishuMessages(
        new FeishuProperties(
            null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));
  }
}
