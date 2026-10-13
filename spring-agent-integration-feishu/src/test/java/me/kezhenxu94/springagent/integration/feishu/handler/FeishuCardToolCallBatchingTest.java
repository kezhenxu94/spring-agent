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
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How many calls to Feishu a turn's tool calls cost, on a card as a run actually gets one: paced,
 * and written by a worker of its own.
 *
 * <p>A call used to cost two — its pane appended as it went out, waited on, and rewritten as it
 * came back — whatever else was queued and however soon it returned. What is asserted here is that
 * the calls ride the card's queue like the answer does: a call that comes back before its pane has
 * gone out is sent once, the calls of one round go out as one insert, and the tool is not kept
 * waiting on any of it.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardToolCallBatchingTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @TempDir Path userHomeRoot;

  private final JsonMapper om = new JsonMapper();

  private final FeishuMessages messages =
      new FeishuMessages(
          new FeishuProperties(
              null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));

  /** Every insert, as {@code type:target} and the elements it carried, in the order made. */
  private final List<Write> creates = Collections.synchronizedList(new ArrayList<>());

  /** Every element rewritten whole, by id, in the order made. */
  private final List<String> updates = Collections.synchronizedList(new ArrayList<>());

  /** Held before an append returns, for the test that needs one still in flight. */
  private final CountDownLatch releaseAppends = new CountDownLatch(1);

  private volatile boolean holdAppends;

  private ScheduledExecutorService clock;
  private ExecutorService writes;

  private record Write(String type, String target, JsonNode elements) {}

  @BeforeEach
  void setUp() throws Exception {
    final var created = new CreateCardElementResp();
    created.setCode(0);
    lenient()
        .when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenAnswer(
            invocation -> {
              final var body =
                  invocation.<CreateCardElementReq>getArgument(0).getCreateCardElementReqBody();
              creates.add(
                  new Write(
                      body.getType(), body.getTargetElementId(), om.readTree(body.getElements())));
              if (holdAppends && "append".equals(body.getType())) {
                releaseAppends.await(10, TimeUnit.SECONDS);
              }
              return created;
            });
    final var replaced = new UpdateCardElementResp();
    replaced.setCode(0);
    lenient()
        .when(feishu.cardkit().v1().cardElement().update(any(UpdateCardElementReq.class)))
        .thenAnswer(
            invocation -> {
              updates.add(invocation.<UpdateCardElementReq>getArgument(0).getElementId());
              return replaced;
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
    releaseAppends.countDown();
    clock.shutdownNow();
    writes.shutdownNow();
  }

  @Test
  @DisplayName("calls made and returned inside one interval go out as one append, results and all")
  void aRoundOfCallsIsOneAppend() {
    final var card = card(Duration.ofSeconds(30));
    final var run = run(card);
    // The turn's first call puts the pane on the card, and goes out at once: the card has said
    // nothing yet, so there is no interval to wait out. Everything below is inside it.
    run.setToolStatus("Bash", "{\"command\":\"ls\"}", null);
    assertThat(creates).extracting(Write::type).containsExactly("insert_before");

    run.setToolStatus("Read", "{\"path\":\"/etc/hosts\"}", null);
    run.setToolStatus("Grep", "{\"pattern\":\"localhost\"}", null);
    run.clearToolStatus("Read", "{\"path\":\"/etc/hosts\"}", "127.0.0.1 localhost");
    card.finish();

    assertThat(creates).extracting(Write::type).containsExactly("insert_before", "append");
    final var appended = creates.get(1).elements();
    assertThat(appended).hasSize(2);
    assertThat(appended.path(0).path("element_id").asString()).isEqualTo("tool_call_1");
    assertThat(appended.path(0).toString()).contains("127.0.0.1 localhost");
    assertThat(appended.path(1).path("element_id").asString()).isEqualTo("tool_call_2");
    // The call that came back was folded into the append rather than rewritten after it.
    assertThat(updates).doesNotContain("tool_call_1");
  }

  @Test
  @DisplayName("a call that comes back after its pane went out rewrites that pane alone")
  void aLateResultRewritesItsOwnPane() {
    final var card = card(Duration.ZERO);
    final var run = run(card);
    run.setToolStatus("Bash", "{\"command\":\"ls\"}", null);
    run.setToolStatus("Read", "{\"path\":\"/etc/hosts\"}", null);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(creates).extracting(Write::type).contains("append"));

    run.clearToolStatus("Read", "{\"path\":\"/etc/hosts\"}", "127.0.0.1 localhost");
    card.finish();

    assertThat(updates).containsExactly("tool_call_1");
  }

  @Test
  @DisplayName("a tool is not kept waiting while its pane is written to the card")
  void theToolDoesNotWaitOnTheCard() throws Exception {
    final var card = card(Duration.ZERO);
    final var run = run(card);
    run.setToolStatus("Bash", "{\"command\":\"ls\"}", null);
    holdAppends = true;

    // Returns while the append it queued is held on the worker: this is the tool's own thread, and
    // the tool used to run only once Feishu had answered.
    run.setToolStatus("Read", "{\"path\":\"/etc/hosts\"}", null);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(creates).extracting(Write::type).contains("append"));
    assertThat(releaseAppends.getCount()).isEqualTo(1);
  }

  private FeishuCard card(final Duration interval) {
    return new FeishuCard(
        feishu, "card-1", null, new UserHome(userHomeRoot), messages, interval, 0, clock, writes);
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
