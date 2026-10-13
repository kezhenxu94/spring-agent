package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The writes a run used to wait on, which it no longer does: putting one of its elements on the
 * card as it first has something to put there, and finishing the card. Each held the thread that
 * asked — the one consuming the model's stream, a tool's own, the run's end — for at least a round
 * trip to Feishu.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardQueuedInsertTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @TempDir Path userHomeRoot;

  private final JsonMapper om = new JsonMapper();

  private final FeishuMessages messages =
      new FeishuMessages(
          new FeishuProperties(
              null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));

  /** Every insert, as the element it put on and the idempotency key it carried. */
  private final List<String> inserts = Collections.synchronizedList(new ArrayList<>());

  /** How many inserts to refuse before taking any, counted down as each is refused. */
  private final AtomicInteger refuseInserts = new AtomicInteger();

  /** Held before a write returns, for the tests that need one still in flight. */
  private final CountDownLatch release = new CountDownLatch(1);

  private volatile boolean holdInserts;
  private volatile boolean holdClosing;

  private ScheduledExecutorService clock;
  private ExecutorService writes;

  @BeforeEach
  void setUp() throws Exception {
    lenient()
        .when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenAnswer(
            invocation -> {
              final var body =
                  invocation.<CreateCardElementReq>getArgument(0).getCreateCardElementReqBody();
              final var response = new CreateCardElementResp();
              if (refuseInserts.getAndUpdate(left -> Math.max(left - 1, 0)) > 0) {
                response.setCode(1);
                response.setMsg("refused");
                return response;
              }
              inserts.add(
                  om.readTree(body.getElements()).path(0).path("element_id").asString()
                      + "="
                      + body.getUuid());
              if (holdInserts) {
                release.await(10, TimeUnit.SECONDS);
              }
              response.setCode(0);
              return response;
            });
    final var streamed = new ContentCardElementResp();
    streamed.setCode(0);
    lenient()
        .when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenReturn(streamed);
    final var deleted = new DeleteCardElementResp();
    deleted.setCode(0);
    lenient()
        .when(feishu.cardkit().v1().cardElement().delete(any(DeleteCardElementReq.class)))
        .thenAnswer(
            invocation -> {
              if (holdClosing) {
                release.await(10, TimeUnit.SECONDS);
              }
              return deleted;
            });
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
    release.countDown();
    clock.shutdownNow();
    writes.shutdownNow();
  }

  @Test
  @DisplayName("the answer's first chunk does not wait for its element to be put on the card")
  void theFirstChunkDoesNotWait() {
    holdInserts = true;
    final var run = run(paced());

    // Returns while the insert putting the answer's element on the card is held on the worker:
    // this is the thread consuming the model's stream, and it used to wait for Feishu here.
    run.onContent("The quick brown fox");

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(inserts).anyMatch(i -> i.startsWith("message=")));
    assertThat(release.getCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("an element whose insert was refused is put on again by the next write to it")
  void aRefusedInsertIsPutOnAgain() {
    refuseInserts.set(1);
    final var run =
        run(new FeishuCard(feishu, "card-1", null, new UserHome(userHomeRoot), messages));

    run.onContent("The quick");
    assertThat(inserts).isEmpty();
    run.onContent("The quick brown fox");

    // Under a key of its own: Feishu may hold on to what it answered the first.
    assertThat(inserts).singleElement().asString().startsWith("message=").contains(":again:");
  }

  @Test
  @DisplayName("finishing returns before the card is closed, and says when it has been")
  void finishingDoesNotWait() {
    holdClosing = true;
    final var card = paced();

    final var finished = card.finish();

    assertThat(finished).isNotDone();
    release.countDown();
    assertThat(finished.join()).isTrue();
  }

  private FeishuCard paced() {
    return new FeishuCard(
        feishu,
        "card-1",
        null,
        new UserHome(userHomeRoot),
        messages,
        Duration.ZERO,
        0,
        clock,
        writes);
  }

  private FeishuCardUpdater run(final FeishuCard card) {
    return FeishuCardUpdater.forRun(
        card,
        om,
        null,
        messages,
        new FeishuCardElements(
            om, messages, new ClassPathResource("feishu/card-elements.json"), null, true),
        null);
  }
}
