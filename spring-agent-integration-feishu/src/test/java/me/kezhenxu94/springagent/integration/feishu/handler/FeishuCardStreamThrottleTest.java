package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
 * A run streams faster than a card can be written, and every chunk it produces is the whole answer
 * so far. So a card holds what it is given and sends the newest state once its interval has passed
 * — the reader loses nothing, since only the newest state was ever going to be on screen, and the
 * run stops paying a round trip per chunk.
 *
 * <p>What has to hold is that nothing is lost at the end of it: the last thing said when the model
 * stops still reaches the card, whether the next chunk carries it out, the clock does, or the run
 * ending does.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardStreamThrottleTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @TempDir Path userHomeRoot;

  /** What each streaming call actually carried, in the order the SDK saw them. */
  private final List<String> written = Collections.synchronizedList(new ArrayList<>());

  private ScheduledExecutorService flushes;

  @BeforeEach
  void setUp() throws Exception {
    final var ok = new ContentCardElementResp();
    ok.setCode(0);
    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenAnswer(
            invocation -> {
              final ContentCardElementReq request = invocation.getArgument(0);
              written.add(request.getContentCardElementReqBody().getContent());
              return ok;
            });
    flushes = Executors.newScheduledThreadPool(1);
  }

  @AfterEach
  void tearDown() {
    flushes.shutdownNow();
  }

  private FeishuCard card(final Duration interval, final int characters) {
    return new FeishuCard(
        feishu,
        "card-1",
        null,
        new UserHome(userHomeRoot),
        messages(),
        interval,
        characters,
        flushes,
        // On the calling thread: what is under test is the policy — when a write goes out — and
        // asserting on that is simplest where the write has been made by the time stream() returns.
        // That the calls are made off that thread is FeishuCardAsyncWriteTest's.
        null);
  }

  @Test
  @DisplayName("a burst of chunks costs one call, carrying the newest of them")
  void chunksAreCoalesced() throws Exception {
    cardCanBeFinished();
    final var card = card(Duration.ofSeconds(30), 0);
    // The first is what makes the card stop looking empty, so it goes out at once; the rest are
    // inside the interval and are held.
    card.stream("message", "The");
    card.stream("message", "The quick");
    card.stream("message", "The quick brown");

    assertThat(written).containsExactly("The");

    card.finish();

    assertThat(written).containsExactly("The", "The quick brown");
  }

  @Test
  @DisplayName("enough characters piling up gets written before the interval says so")
  void enoughCharactersGoOutEarly() {
    final var card = card(Duration.ofSeconds(30), 10);
    card.stream("message", "a");
    card.stream("message", "a".repeat(4));
    assertThat(written).containsExactly("a");

    card.stream("message", "a".repeat(20));

    assertThat(written).containsExactly("a", "a".repeat(20));
  }

  @Test
  @DisplayName("a run that goes quiet still leaves the card showing what it last said")
  void whatIsHeldBackIsWrittenOnTheClock() {
    final var card = card(Duration.ofMillis(100), 0);
    card.stream("message", "The");
    card.stream("message", "The quick brown fox");

    // Nothing follows it — a tool call the run is waiting out, or the end of the answer before
    // anything finalizes the card — so the clock is what carries it.
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(written).containsExactly("The", "The quick brown fox"));
  }

  @Test
  @DisplayName("writing the same content again costs no call")
  void unchangedContentIsNotResent() {
    final var card = card(Duration.ofMillis(1), 0);
    card.stream("message", "The quick brown fox");
    card.stream("message", "The quick brown fox");

    assertThat(written).containsExactly("The quick brown fox");
  }

  @Test
  @DisplayName("a card left unthrottled writes every chunk through as it arrives")
  void noIntervalMeansNoBuffering() {
    final var card = new FeishuCard(feishu, "card-1", null, new UserHome(userHomeRoot), messages());
    card.stream("message", "The");
    card.stream("message", "The quick");

    assertThat(written).containsExactly("The", "The quick");
  }

  private FeishuMessages messages() {
    return new FeishuMessages(
        new FeishuProperties(null, null, null, null, null, null, null, Locale.ENGLISH, null, null));
  }

  /** Finishing a card takes the stop button off it and closes streaming mode; both just succeed. */
  private void cardCanBeFinished() throws Exception {
    final var deleted = new DeleteCardElementResp();
    deleted.setCode(0);
    when(feishu.cardkit().v1().cardElement().delete(any(DeleteCardElementReq.class)))
        .thenReturn(deleted);
    final var settings = new SettingsCardResp();
    settings.setCode(0);
    when(feishu.cardkit().v1().card().settings(any(SettingsCardReq.class))).thenReturn(settings);
  }
}
