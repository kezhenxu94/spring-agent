package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementResp;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.kezhenxu94.springagent.core.tools.UserHome;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
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
 * The sequence belongs to the card, not to the element: a write to one element has to carry a
 * higher number than the write to any other element before it, or the card refuses it and every
 * write after it. A card now has several writers — the run and a panel per subagent — so this is
 * the invariant that decides whether splitting them was safe.
 *
 * <p>Asserted on the numbers as the API sees them, recorded when the call is made rather than
 * afterwards: what matters is not which numbers were handed out but that they arrive in order.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardSequenceTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @TempDir Path userHomeRoot;

  private FeishuCard card;

  /** Every sequence the card sent, in the order the SDK was actually called. */
  private final List<Integer> sequences = Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  void setUp() throws Exception {
    final var ok = new ContentCardElementResp();
    ok.setCode(0);
    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenAnswer(
            invocation -> {
              final ContentCardElementReq request = invocation.getArgument(0);
              sequences.add(request.getContentCardElementReqBody().getSequence());
              return ok;
            });
    final var inserted = new CreateCardElementResp();
    inserted.setCode(0);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenAnswer(
            invocation -> {
              final CreateCardElementReq request = invocation.getArgument(0);
              sequences.add(request.getCreateCardElementReqBody().getSequence());
              return inserted;
            });

    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null));
    card = new FeishuCard(feishu, "card-1", null, new UserHome(userHomeRoot), messages);
  }

  @Test
  @DisplayName("two updaters writing to one card leave the sequence strictly increasing")
  void writersShareOneIncreasingSequence() throws Exception {
    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null));
    final var panels = panels(messages);
    final var run =
        FeishuCardUpdater.forRun(
            card, new JsonMapper(), null, messages, cardElements(messages), null);
    card.insertBeforeFooter(
        panels.forInsert("sub_1", "Reading the log", "Read the log and say what broke", null),
        "sub_1");
    final var subagent =
        FeishuCardUpdater.forSubagent(
            card,
            new JsonMapper(),
            null,
            messages,
            panels,
            "sub_1",
            "Reading the log",
            "Read the log and say what broke");

    // Both at once, which is what a run does while a subagent of it is working.
    final var start = new CountDownLatch(1);
    final var writers =
        List.of(
            writer(start, () -> run.onContent("the run is still writing")),
            writer(start, () -> subagent.onContent("so is the subagent")));
    writers.forEach(Thread::start);
    start.countDown();
    for (final var writer : writers) {
      writer.join(TimeUnit.SECONDS.toMillis(10));
    }

    assertThat(sequences).hasSizeGreaterThan(2);
    assertThat(sequences).isSorted();
    // Sorted is not enough: the same number twice would also be sorted, and the card refuses a
    // sequence that did not strictly increase.
    assertThat(sequences).doesNotHaveDuplicates();
  }

  private Thread writer(final CountDownLatch start, final Runnable write) {
    return new Thread(
        () -> {
          try {
            start.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          for (var i = 0; i < 50; i++) {
            write.run();
          }
        });
  }

  private static FeishuSubagentPanel panels(final FeishuMessages messages) {
    final var panels = new FeishuSubagentPanel(new JsonMapper(), messages);
    panels.subagentPanel =
        new org.springframework.core.io.ClassPathResource("feishu/subagent-panel.json");
    return panels;
  }

  /** The real elements: what the card gains as the run first has something to put in them. */
  private static FeishuCardElements cardElements(final FeishuMessages messages) {
    return new FeishuCardElements(
        new JsonMapper(), messages, new ClassPathResource("feishu/card-elements.json"), null);
  }
}
