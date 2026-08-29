package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.lark.oapi.service.im.v1.model.EventMessage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.core.dao.repo.SituationRepo;
import me.kezhenxu94.springagent.integration.feishu.handler.FeishuChatObservations;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

/**
 * The case this feature was actually asked for: the agent reads a group chat it was not addressed
 * in, and works out whether it has anything worth saying.
 *
 * <p>Different enough from the webhook path to be worth its own end-to-end run, because almost
 * everything about it is different. The observation arrives over a websocket rather than HTTP; the
 * run is told to look for an unanswered question rather than to assess a failure; and one look ends
 * the situation, because the next thing said in the chat is a new question rather than more of this
 * one. All three are configuration, and configuration is what a unit test cannot check.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      // Stated, because the prompts and the brief are translated now: left unset they follow
      // the machine's locale, and these assertions are about English wording.
      "app.locale=en",
      "app.events.enabled=true",
      // Named, one chat at a time. Nothing is observed until a deployment does this.
      "app.feishu.observed-chat-ids[0]=" + ChatObservationEndToEndTest.CHAT,
      "app.feishu.observed-chat-ids[1]=" + ChatObservationEndToEndTest.OTHER_CHAT,
      "app.events.sources.feishu-chat.owner-user-id=ou_agent",
      // Per-source, and it has to be: the top-level app.events.debounce does not reach this source,
      // because EventsProperties.BUILT_IN gives a chat forty-five seconds of its own so that the
      // people in it get to answer each other first. Overriding it globally here would leave the
      // test waiting out that forty-five seconds, which is how this was first written.
      "app.events.sources.feishu-chat.debounce=PT0S",
      "app.events.sources.feishu-chat.cooldown=PT0S",
      "app.events.sweep-interval=PT1S",
      // resolve-after-evaluation and the chat prompt are deliberately NOT set here. They come from
      // EventsProperties.BUILT_IN, and two of the assertions below are about exactly that.
      "spring.ai.chat.client.tool-search-advisor.enabled=false"
    })
class ChatObservationEndToEndTest extends AbstractIntegrationTest {

  static final String CHAT = "oc_platform_team";

  /**
   * A second watched chat, so the two tests that observe something do not correlate into one
   * situation.
   *
   * <p>They would otherwise: the correlation key for this source is the chat and nothing else,
   * which is the intended behaviour and exactly what makes one shared chat id unusable across test
   * methods that share a context and a database.
   */
  static final String OTHER_CHAT = "oc_release_team";

  @Autowired FeishuChatObservations observations;
  @Autowired SituationRepo situations;
  @Autowired ChimeInChatModel model;

  @Test
  @DisplayName("a question nobody addressed to the bot becomes a look, on the chat's own terms")
  void shouldObserveAChatAndDecideWhetherToChimeIn() {
    observations.observed(
        message("om_1", "how do I rotate the gateway certificate?"), "ou_alice", "tenant-1");

    final var situation = awaitAssessed();

    assertThat(situation.source()).isEqualTo("feishu-chat");
    // One rolling window per chat, not per topic: deciding two messages are about the same thing
    // would take embeddings and would split conversations in half often enough to matter.
    assertThat(situation.correlationKey()).isEqualTo("feishu-chat:" + CHAT);
    // The chat it came from, so a run about it talks back there rather than into a configured one.
    assertThat(situation.chatId()).isEqualTo(CHAT);
    assertThat(situation.tenantId()).isEqualTo("tenant-1");
    assertThat(situation.decision()).isEqualTo(Situation.Decision.ACTED);
    assertThat(situation.assessment()).contains("answered the certificate question");

    // Closed after one look, from the built-in policy for this source and nothing this test set.
    assertThat(situation.status()).isEqualTo(Situation.Status.RESOLVED);
    // So the next thing said in the chat opens a new situation rather than reopening this one.
    assertThat(
            situations.findByCorrelationKeyAndStatus("feishu-chat:" + CHAT, Situation.Status.OPEN))
        .isEmpty();
  }

  @Test
  @DisplayName("it is asked whether to speak, and is given the means to")
  void shouldUseTheChatPromptAndOfferAWayToSpeak() {
    observations.observed(
        messageIn(OTHER_CHAT, "om_2", "does anyone know why the build is red?"),
        "ou_bob",
        "tenant-1");
    awaitAssessed(OTHER_CHAT);

    final var prompt = promptAbout(OTHER_CHAT);
    final var text = prompt.getContents();
    // The chat prompt, not the general one. Nothing is wrong here; the question is whether there is
    // a question — and the failure mode of this feature is not silence but a bot that greets,
    // agrees and summarises, so the instructions that forbid that are worth asserting.
    assertThat(text).contains("reading a group chat that you were not addressed in");
    assertThat(text).contains("still unanswered, that you can answer well");
    assertThat(text).contains("Do not speak to greet");
    assertThat(text).doesNotContain("deserves anybody's attention");
    // Who spoke is in the evidence, since Observation has no field for it on purpose.
    assertThat(text).contains("ou_bob").contains("why the build is red");

    // And it can actually say something, which is the whole point of the run.
    final var offered =
        ((ToolCallingChatOptions) prompt.getOptions())
            .getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    assertThat(offered).contains("FeishuSendMessage", "RecordSituationAssessment");
    // Still not able to leave work behind that nobody would answer for.
    assertThat(offered).doesNotContain("CreateScheduledTask");
  }

  @Test
  @DisplayName("a chat nobody named is not observed at all")
  void shouldIgnoreAnUnwatchedChat() {
    // The default state of the feature, and the reason it is a privacy decision rather than a
    // performance one: nothing about the conversation is stored anywhere.
    observations.observed(
        messageIn("oc_someone_elses_chat", "om_3", "private conversation"), "ou_carol", "tenant-1");

    assertThat(
            situations.findByCorrelationKeyAndStatus(
                "feishu-chat:oc_someone_elses_chat", Situation.Status.OPEN))
        .isEmpty();
  }

  private Situation awaitAssessed() {
    return awaitAssessed(CHAT);
  }

  private Situation awaitAssessed(final String chatId) {
    // Generous, because this waits on a real debounce, a real sweep and a real run, and the suite
    // runs up to eight of these forks at once — a wait tuned to an idle machine is a test that
    // fails
    // for reasons that have nothing to do with the code.
    Awaitility.await()
        .atMost(Duration.ofSeconds(180))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(assessed(chatId))
                    // What the situation actually looked like when the wait ran out, since "empty
                    // Optional" says nothing about whether it was never observed, never swept, or
                    // ran and failed.
                    .as("assessed situation for %s; stored: %s", chatId, storedFor(chatId))
                    .isPresent());
    return assessed(chatId).orElseThrow();
  }

  /** Every situation for one chat, whatever state it reached, for a failure message. */
  private String storedFor(final String chatId) {
    return java.util.stream.Stream.of(Situation.Status.OPEN, Situation.Status.RESOLVED)
        .flatMap(
            status ->
                situations.findByCorrelationKeyAndStatus("feishu-chat:" + chatId, status).stream())
        .map(
            s ->
                "[phase=%s status=%s decision=%s events=%s lastError=%s]"
                    .formatted(s.phase(), s.status(), s.decision(), s.eventCount(), s.lastError()))
        .toList()
        .toString();
  }

  /** Looked for under both statuses, since this source closes a situation after one look. */
  private java.util.Optional<Situation> assessed(final String chatId) {
    return java.util.stream.Stream.of(Situation.Status.OPEN, Situation.Status.RESOLVED)
        .flatMap(
            status ->
                situations.findByCorrelationKeyAndStatus("feishu-chat:" + chatId, status).stream())
        .filter(situation -> situation.decision() != null)
        .findFirst();
  }

  /** The brief a run about one chat was given, rather than whichever ran first. */
  private Prompt promptAbout(final String chatId) {
    return model.prompts.stream()
        .filter(prompt -> prompt.getContents().contains(chatId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no run was given a brief about " + chatId));
  }

  private static EventMessage message(final String messageId, final String text) {
    return messageIn(CHAT, messageId, text);
  }

  private static EventMessage messageIn(
      final String chatId, final String messageId, final String text) {
    final var message = new EventMessage();
    message.setMessageId(messageId);
    message.setChatId(chatId);
    message.setChatType("group");
    message.setMessageType("text");
    message.setContent("{\"text\":\"" + text + "\"}");
    return message;
  }

  /** Answers the question, the way the prompt asks it to when it is confident. */
  static final class ChimeInChatModel implements ChatModel {

    final List<Prompt> prompts = new CopyOnWriteArrayList<>();

    @Override
    public ToolCallingChatOptions getOptions() {
      return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(final Prompt prompt) {
      throw new UnsupportedOperationException("the agent only streams");
    }

    @Override
    public Flux<ChatResponse> stream(final Prompt prompt) {
      prompts.add(prompt);
      // Decided from the prompt rather than from a flag, so every run behaves the same way. A
      // one-shot flag would make the first run call the tool and every run after it merely talk,
      // which lets a later test pass on the first test's stored assessment.
      final var alreadyCalled =
          prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
      if (!alreadyCalled) {
        return Flux.just(
            new ChatResponse(
                List.of(
                    new Generation(
                        AssistantMessage.builder()
                            .content("")
                            .toolCalls(
                                List.of(
                                    new AssistantMessage.ToolCall(
                                        "call-1",
                                        "function",
                                        "RecordSituationAssessment",
                                        """
                                        {"decision":"ACTED",\
                                        "summary":"Nobody had replied, so I answered the certificate question.",\
                                        "confidence":0.9}\
                                        """)))
                            .build()))));
      }
      return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Done.")))));
    }
  }

  @TestConfiguration
  static class ChimeInModelConfiguration {

    @Bean
    @Primary
    ChimeInChatModel chimeInChatModel() {
      return new ChimeInChatModel();
    }
  }
}
