package me.kezhenxu94.springagent.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Who a message queued mid-run is said to be from.
 *
 * <p>A queued message is added to the turn as a plain user message, so the frame around it is the
 * only place left to record authorship. An administrator may speak into a run that is not theirs —
 * see {@code SpringAgent#liveRunFor} — and read with the ordinary frame their words would arrive as
 * the person being helped changing their mind.
 */
class QueuedMessagesTest {

  private static final String OWNER = "ou_owner";

  @Test
  @DisplayName("the owner's own message is framed as their own words")
  void shouldFrameTheOwnersMessageAsTheirs() {
    final var queue = queueFor(OWNER);
    queue.offer(from(OWNER, "actually, use Postgres"));

    final var read = queue.read();

    assertThat(read).singleElement().asString().contains("actually, use Postgres");
    assertThat(read.getFirst()).doesNotContain("administrator");
  }

  @Test
  @DisplayName("somebody else's message says whose it is, and that it outranks")
  void shouldNameAnAdministratorWhoSpokeIntoTheRun() {
    final var queue = queueFor(OWNER);
    queue.offer(from("ou_admin", "stop, that cluster is production"));

    final var read = queue.read();

    assertThat(read)
        .singleElement()
        .asString()
        .contains("stop, that cluster is production")
        .contains("ou_admin")
        .contains("administrator");
  }

  @Test
  @DisplayName("each message is framed by its own sender, not by the batch")
  void shouldFrameEachMessageBySender() {
    final var queue = queueFor(OWNER);
    queue.offer(from(OWNER, "mine"));
    queue.offer(from("ou_admin", "theirs"));

    final var read = queue.read();

    assertThat(read).hasSize(2);
    assertThat(read.get(0)).contains("mine").doesNotContain("ou_admin");
    assertThat(read.get(1)).contains("theirs").contains("ou_admin");
  }

  @Test
  @DisplayName("a message whose text cannot be produced is dropped, not thrown out of the run")
  void shouldDropAMessageItCannotRead() {
    final var queue = queueFor(OWNER);
    queue.offer(
        new QueuedMessages.Queued(
            request(OWNER),
            () -> {
              throw new IllegalStateException("the download failed");
            }));
    queue.offer(from(OWNER, "readable"));

    assertThat(queue.read()).singleElement().asString().contains("readable");
  }

  @Test
  @DisplayName("a closed queue takes nothing more and hands back what was never read")
  void shouldHandBackTheUnread() {
    final var queue = queueFor(OWNER);
    queue.offer(from(OWNER, "never read"));

    assertThat(queue.close()).hasSize(1);
    assertThat(queue.offer(from(OWNER, "too late"))).isFalse();
    assertThat(queue.read()).isEmpty();
  }

  @Test
  @DisplayName("what was read is said out loud once, naming the requests")
  void shouldReportWhatItRead() {
    final var reported = new ArrayList<List<String>>();
    final var queue = new QueuedMessages(OWNER, messages(), reported::add);
    queue.offer(from(OWNER, "one"));
    queue.offer(from("ou_admin", "two"));

    queue.read();

    assertThat(reported)
        .singleElement()
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
        .hasSize(2);
    // Nothing read, nothing said: a run polls this between every pair of tool calls.
    queue.read();
    assertThat(reported).hasSize(1);
  }

  private static QueuedMessages queueFor(final String owner) {
    return new QueuedMessages(owner, messages(), read -> {});
  }

  private static QueuedMessages.Queued from(final String userId, final String text) {
    return new QueuedMessages.Queued(request(userId), () -> text);
  }

  private static AgentRequest request(final String userId) {
    return AgentRequest.builder()
        .requestId("req-" + userId)
        .scenario(BuiltInScenarios.CHAT)
        .userId(userId)
        .chatId("oc_1")
        .conversationId("om_root")
        .userMessage(user -> user.text("ignored: the queue reads the supplier"))
        .build();
  }

  private static CoreMessages messages() {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    return new CoreMessages(source, new SpringAgentProperties(null, null, Locale.ENGLISH, null));
  }
}
