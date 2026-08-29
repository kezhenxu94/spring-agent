package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Covers what the tools decide, which is everything except the run itself: what a subagent is
 * started as, that an answer comes back to whoever waited for it, and that the run cannot start
 * more than it is allowed or reach a subagent that is not its own.
 */
class SubagentToolsTest {

  private final SpringAgent springAgent = mock(SpringAgent.class);

  private final SubagentTools tools =
      new SubagentTools(springAgent, properties(2), messages(Locale.ENGLISH));

  private final ToolContext context = contextOf("req-1");

  @BeforeEach
  void setUp() {
    when(springAgent.accepting()).thenReturn(true);
  }

  @Test
  @DisplayName("a subagent is a run of its own with nothing of the conversation about it")
  void aSubagentIsItsOwnRun() {
    final var result = tools.startSubagent("Reading the timeline", "Read x and report y", context);

    final var started = fired();
    assertThat(result).contains(started.requestId());
    assertThat(started.scenario()).isEqualTo(BuiltInScenarios.SUBAGENT);
    assertThat(started.parentRequestId()).isEqualTo("req-1");
    assertThat(started.description()).isEqualTo("Reading the timeline");
    // The user, so it works in the same workspace; not the thread, so no surface adopts it and it
    // is not offered the ask.
    assertThat(started.userId()).isEqualTo("ou_1");
    assertThat(started.chatId()).isEqualTo("oc_1");
    // The scopes the shell sandbox mounts by, so a Bash call made from inside the subagent still
    // reaches the group's and the tenant's files, not only the user's own.
    assertThat(started.groupId()).isEqualTo("oc_group_1");
    assertThat(started.tenantId()).isEqualTo("tenant_1");
    assertThat(started.rootMessageId()).isNull();
    assertThat(started.replyMessageId()).isNull();
    assertThat(started.background()).isTrue();
    // Its own conversation, so nothing it says can be read back into the user's thread.
    assertThat(started.conversationId()).isEqualTo(started.requestId());
  }

  @Test
  @DisplayName("the brief reaches the model wrapped in what a subagent is told about being one")
  void theBriefIsWrapped() {
    tools.startSubagent("Reading the timeline", "Read x and report y", context);

    assertThat(userMessageOf(fired()))
        .contains("You are running as a subagent")
        .contains("Read x and report y");
  }

  @Test
  @DisplayName("waiting hands back what the subagent reported")
  void waitingReturnsTheAnswer() {
    final var id = idOf(tools.startSubagent("Reading the timeline", "Read x", context));
    finish(fired(), "the timeline starts on Monday", AgentOutcome.COMPLETED);

    assertThat(tools.waitForSubagent(id, context)).contains("the timeline starts on Monday");
  }

  @Test
  @DisplayName("a subagent that failed is said to have failed, rather than answered with nothing")
  void aFailedSubagentIsReportedAsSuch() {
    final var id = idOf(tools.startSubagent("Reading the timeline", "Read x", context));
    final var request = fired();
    request.listeners().forEach(l -> l.onError(new IllegalStateException("the file is gone")));
    request.listeners().forEach(l -> l.onFinished(AgentOutcome.FAILED));

    assertThat(tools.waitForSubagent(id, context)).contains("failed").contains("the file is gone");
  }

  @Test
  @DisplayName("a subagent that reported nothing is not passed off as an empty answer")
  void anEmptyAnswerIsSaidToBeOne() {
    final var id = idOf(tools.startSubagent("Reading the timeline", "Read x", context));
    finish(fired(), "   ", AgentOutcome.COMPLETED);

    assertThat(tools.waitForSubagent(id, context)).contains("without reporting anything");
  }

  @Test
  @DisplayName("more subagents than the limit are refused rather than started")
  void theLimitIsEnforced() {
    tools.startSubagent("one", "do one", context);
    tools.startSubagent("two", "do two", context);

    final var refused = tools.startSubagent("three", "do three", context);

    assertThat(refused).contains("2 subagents are already running");
    verify(springAgent, org.mockito.Mockito.times(2)).fire(any());
  }

  @Test
  @DisplayName("a subagent that has finished frees its place under the limit")
  void finishingFreesAPlace() {
    tools.startSubagent("one", "do one", context);
    tools.startSubagent("two", "do two", context);
    finish(firedRequests().get(0), "done", AgentOutcome.COMPLETED);

    assertThat(tools.startSubagent("three", "do three", context)).contains("Started subagent");
  }

  @Test
  @DisplayName("a run cannot wait for or stop a subagent that is not its own")
  void aSubagentBelongsToTheRunThatStartedIt() {
    final var id = idOf(tools.startSubagent("Reading the timeline", "Read x", context));

    final var elsewhere = contextOf("req-2");
    assertThat(tools.waitForSubagent(id, elsewhere)).contains("No subagent");
    assertThat(tools.cancelSubagent(id, elsewhere)).contains("No subagent");
    verify(springAgent, never()).cancel(any());
  }

  @Test
  @DisplayName("stopping a subagent stops the run behind it")
  void cancellingStopsTheRun() {
    final var id = idOf(tools.startSubagent("Reading the timeline", "Read x", context));

    assertThat(tools.cancelSubagent(id, context)).contains("is being stopped");
    verify(springAgent).cancel(id);
  }

  @Test
  @DisplayName("stopping one that has already finished is said to be nothing to do")
  void cancellingAFinishedSubagentDoesNothing() {
    final var id = idOf(tools.startSubagent("Reading the timeline", "Read x", context));
    finish(fired(), "done", AgentOutcome.COMPLETED);

    assertThat(tools.cancelSubagent(id, context)).contains("had already finished");
    verify(springAgent, never()).cancel(any());
  }

  @Test
  @DisplayName("nothing is started while the application is shutting down")
  void nothingStartsDuringShutdown() {
    when(springAgent.accepting()).thenReturn(false);

    assertThat(tools.startSubagent("one", "do one", context)).contains("COULD NOT START");
    verify(springAgent, never()).fire(any());
  }

  @Test
  @DisplayName("a subagent that could not be started leaves nothing behind to wait for")
  void aSubagentThatCouldNotStartIsNotLeftRunning() {
    // The tool remembers the subagent before firing it, and only a run that actually started ever
    // reports itself finished. So a fire() that throws used to leave behind a subagent that was
    // forever running: it held a place under the limit, and the first wait for it never returned.
    org.mockito.Mockito.doThrow(new IllegalStateException("no listener bean"))
        .when(springAgent)
        .fire(any());

    final var refused = tools.startSubagent("one", "do one", context);

    assertThat(refused).contains("COULD NOT START").contains("no listener bean");
    // Nothing to wait for, and the place it briefly took is free again.
    assertThat(tools.waitForSubagent("sub_whatever", context)).contains("No subagent");
    org.mockito.Mockito.reset(springAgent);
    when(springAgent.accepting()).thenReturn(true);
    assertThat(tools.startSubagent("two", "do two", context)).contains("Started subagent");
  }

  @Test
  @DisplayName("waiting on a subagent still working hands the turn back rather than holding it")
  void aWaitThatTimesOutTellsTheModelToAskAgain() {
    // Unbounded, this wait sat on a Reactor worker that the subagent it waited for might need in
    // order to finish. It lets go on a timer instead, and says so in terms that tell the model to
    // come back rather than to give up.
    final var id = idOf(tools.startSubagent("Reading the timeline", "Read x", context));

    final var stillWorking = tools.waitForSubagent(id, context);

    assertThat(stillWorking).contains("still working").contains("WaitForSubagent");
    // And the answer is still there to be collected on the next call.
    finish(fired(), "the timeline starts on Monday", AgentOutcome.COMPLETED);
    assertThat(tools.waitForSubagent(id, context)).contains("the timeline starts on Monday");
  }

  @Test
  @DisplayName("a subagent that outlasts the ceiling is stopped rather than waited on for ever")
  void aSubagentPastTheCeilingIsStopped() {
    final var id = idOf(tools.startSubagent("Reading the timeline", "Read x", context));

    // The ceiling in these tests is 300ms, so a few polls take the wait past it.
    String result;
    do {
      result = tools.waitForSubagent(id, context);
    } while (result.contains("still working"));

    assertThat(result).contains("was still running").contains("has been stopped");
    verify(springAgent).cancel(id);
  }

  @Test
  @DisplayName("a subagent is offered neither subagents of its own nor the scheduler")
  void aSubagentCannotFanOutFurther() {
    assertThat(BuiltInScenarios.SUBAGENT.offers(tools)).isFalse();
    assertThat(BuiltInScenarios.SUBAGENT.offers(mock(ScheduledTaskTool.class))).isFalse();
    assertThat(BuiltInScenarios.SUBAGENT.offers(new DateTimeTool())).isTrue();
    // And it reads no chat memory, so nothing it says joins the conversation it was started from.
    assertThat(BuiltInScenarios.SUBAGENT.conversationMemory()).isFalse();
  }

  /** Drives the run's own listener the way {@code SpringAgent} would as the run ends. */
  private static void finish(
      final AgentRequest request, final String content, final AgentOutcome outcome) {
    request.listeners().forEach(l -> l.onContent(content));
    request.listeners().forEach(l -> l.onFinished(outcome));
  }

  private AgentRequest fired() {
    final var requests = firedRequests();
    return requests.get(requests.size() - 1);
  }

  private List<AgentRequest> firedRequests() {
    final var captor = ArgumentCaptor.forClass(AgentRequest.class);
    verify(springAgent, org.mockito.Mockito.atLeastOnce()).fire(captor.capture());
    return captor.getAllValues();
  }

  /** The id out of what the tool told the model, which is the only place the model reads it. */
  private static String idOf(final String started) {
    final var matcher = java.util.regex.Pattern.compile("sub_[0-9a-f]+").matcher(started);
    assertThat(matcher.find()).as("no subagent id in: " + started).isTrue();
    return matcher.group();
  }

  /** What the run would send the model, taken off the spec the request writes into. */
  private static String userMessageOf(final AgentRequest request) {
    final var spec = mock(ChatClient.PromptUserSpec.class);
    request.userMessage().accept(spec);
    final var text = ArgumentCaptor.forClass(String.class);
    verify(spec).text(text.capture());
    return text.getValue();
  }

  private static ToolContext contextOf(final String requestId) {
    return new ToolContext(
        Map.of(
            ToolContexts.KEY_REQUEST_ID,
            requestId,
            ToolContexts.KEY_USER_ID,
            "ou_1",
            ToolContexts.KEY_CHAT_ID,
            "oc_1",
            ToolContexts.KEY_CHAT_TYPE,
            "p2p",
            ToolContexts.KEY_GROUP_ID,
            "oc_group_1",
            ToolContexts.KEY_TENANT_ID,
            "tenant_1"));
  }

  private static SpringAgentProperties properties(final int maxConcurrent) {
    return new SpringAgentProperties(
        null,
        new SpringAgentProperties.Ai(
            Set.of(),
            Map.of(),
            null,
            null,
            new SpringAgentProperties.Ai.Tools(
                null,
                new SpringAgentProperties.Ai.Tools.Subagent(
                    maxConcurrent,
                    // Short, because a few of these wait on a subagent that has already
                    // finished and the rest on one that never will: the poll is what decides
                    // how long the latter sit there.
                    Duration.ofMillis(100),
                    Duration.ofMillis(300)),
                null),
            null,
            null,
            null),
        Locale.ENGLISH);
  }

  private static CoreMessages messages(final Locale locale) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    return new CoreMessages(source, new SpringAgentProperties(null, null, locale));
  }
}
