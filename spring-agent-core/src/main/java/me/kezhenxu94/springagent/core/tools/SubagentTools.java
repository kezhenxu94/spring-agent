package me.kezhenxu94.springagent.core.tools;

import com.google.common.base.Strings;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentRunRegistry;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Running the agent again, as a tool of its own: work that would fill this run's context — reading
 * a long file, sweeping a cluster, trying three things to see which holds — is handed to a run that
 * has a context of its own, and comes back as one answer.
 *
 * <p>Starting one does not wait for it, so several can be in the air at once and the model decides
 * when it wants each answer. What it cannot do is walk away: {@code SpringAgent} holds a run open
 * until the subagents it started have finished, so an answer nobody collects is still paid for and
 * still reported. The tool descriptions say so.
 *
 * <p>Also an {@link AgentResponseListener} bean, which is how it learns that a run has ended and
 * drops what it remembered about that run. That the bean depends on {@link SpringAgent} is fine:
 * the listeners are resolved lazily there for exactly this reason.
 */
@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class SubagentTools implements AgentResponseListener {

  final SpringAgent springAgent;
  final SpringAgentProperties appConfiguration;
  final CoreMessages messages;

  /**
   * The subagents of each run, by the run's request id. A run's entry is dropped when the run ends,
   * which is also the last moment a subagent of it can still be running — so nothing here outlives
   * the turn that put it there, and a subagent id from an earlier turn is simply unknown.
   */
  private final ConcurrentMap<String, ConcurrentMap<String, SubRun>> subagentsByRun =
      new ConcurrentHashMap<>();

  /**
   * One subagent, and the listener that collects it. {@code content} is what the run has said so
   * far — {@code onContent} hands over the whole answer each time, so the last one is the answer.
   */
  private static final class SubRun implements AgentResponseListener {
    private final String id;
    private final String description;
    private final CountDownLatch done = new CountDownLatch(1);

    /**
     * When the subagent was started, which is what the total wait is measured against. On the run
     * rather than on a wait, because the model may wait, do something else and wait again: what is
     * bounded is how long this subagent may take, not how long one call sat there.
     */
    private final long startedAtNanos = System.nanoTime();

    private volatile String content = "";
    private volatile Throwable error;
    private volatile AgentOutcome outcome;

    private SubRun(final String id, final String description) {
      this.id = id;
      this.description = description;
    }

    private Duration age() {
      return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    @Override
    public void onContent(final String contentSoFar) {
      content = Strings.nullToEmpty(contentSoFar);
    }

    @Override
    public void onError(final Throwable error) {
      this.error = error;
    }

    @Override
    public void onFinished(final AgentOutcome outcome) {
      this.outcome = outcome;
      done.countDown();
    }

    private boolean running() {
      return outcome == null;
    }
  }

  /**
   * Remembers which run each set of subagents belongs to, since a listener is told a run has ended
   * without being told which run. Attached to every run: whether it turns out to start a subagent
   * is not knowable here, and a run that starts none costs one no-op listener.
   */
  @Override
  public void onStart(final AgentRunRegistry registry) {
    final var requestId = registry.request().requestId();
    if (Strings.isNullOrEmpty(requestId)) {
      return;
    }
    registry.addResponseListener(
        new AgentResponseListener() {
          @Override
          public void onFinished(final AgentOutcome outcome) {
            final var forgotten = subagentsByRun.remove(requestId);
            if (forgotten != null) {
              log.info("Run {} ended, forgetting {} subagent(s)", requestId, forgotten.size());
            }
          }
        });
  }

  @Tool(
      name = "StartSubagent",
      description =
"""
Start a subagent: another run of yourself, with its own context window and the same tools, \
working on one task you describe. Use it for work whose middle you do not need to see — \
reading a long file or transcript to answer one question about it, sweeping several \
repositories or clusters, or trying an approach that may take many steps — so that only the \
answer lands in this conversation. Start several and they run at the same time.
The subagent sees nothing of this conversation: not the user message, not what you have found \
so far, not the files you have open. Everything it needs goes in the prompt, written as a \
self-contained brief, and it should say what to report back.
It cannot ask the user anything, so tell it what to assume. It cannot start subagents of its \
own or schedule tasks. It writes to the same workspace you do, so a file it leaves behind is a \
file you can read.
This returns at once with an id. Collect the answer with WaitForSubagent before you finish your \
turn — a subagent you never wait for still runs to the end and still costs its tokens, so if \
you no longer want it, call CancelSubagent.
""")
  public String startSubagent(
      @ToolParam(
              description =
                  "One line in active voice saying what this subagent is for, shown to the user"
                      + " while it works, for example \"Reading the incident timeline\"")
          final String description,
      @ToolParam(
              description =
                  "The subagent's whole brief: the task, every fact it needs to do it, and what to"
                      + " report back. It sees nothing of this conversation.")
          final String prompt,
      final ToolContext context) {

    final var parentRequestId = ToolContexts.require(context, ToolContexts.REQUEST_ID);
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    final var chatId = ToolContexts.get(context, ToolContexts.CHAT_ID);
    final var chatType = ToolContexts.get(context, ToolContexts.CHAT_TYPE);

    if (Strings.isNullOrEmpty(prompt)) {
      return "Error: prompt is required, and has to say the whole task.";
    }
    if (!springAgent.accepting()) {
      return messages.get("subagent-shutting-down");
    }

    final var subagents =
        subagentsByRun.computeIfAbsent(parentRequestId, $ -> new ConcurrentHashMap<>());
    final var max = appConfiguration.ai().tools().subagent().maxConcurrent();
    final var running = subagents.values().stream().filter(SubRun::running).count();
    if (running >= max) {
      return messages.get("subagent-too-many", max);
    }

    final var id = "sub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    final var subRun = new SubRun(id, description);
    subagents.put(id, subRun);

    log.info("Starting subagent {} of run {}: {}", id, parentRequestId, description);
    final var subagentRequest =
        AgentRequest.builder()
            .requestId(id)
            // What ties the two runs together: cancelling this run cancels the subagent, its tokens
            // are counted on this turn, and this run is held open until the subagent has finished.
            .parentRequestId(parentRequestId)
            .description(description)
            .scenario(BuiltInScenarios.SUBAGENT)
            .userId(userId)
            .chatId(chatId)
            .chatType(chatType)
            // Its own, and not the parent's: the scenario attaches no memory advisor either way,
            // and
            // a conversation of its own keeps it out of every store whatever the backend does.
            .conversationId(id)
            // No message to reply onto and nobody watching, deliberately. That is what stops a
            // surface adopting the run — no second card, no second stop button, no output of its
            // own
            // interleaved with this conversation — and what keeps it from being offered the ask.
            .background(true)
            .userMessage(spec -> spec.text(subagentPrompt(prompt)))
            .listener(subRun)
            .build();
    // Guarded because the entry above is what WaitForSubagent waits on, and only a run that was
    // actually started ever releases it. A fire() that threw — a listener bean that cannot be
    // resolved, an Error escaping the fan-out — would otherwise leave behind a subagent that is
    // forever running, holding a place under the limit and hanging the first wait for it.
    try {
      springAgent.fire(subagentRequest);
    } catch (Throwable t) {
      subagents.remove(id);
      log.error("Could not start subagent {} of run {}", id, parentRequestId, t);
      return messages.get("subagent-could-not-start", Strings.nullToEmpty(t.getMessage()));
    }

    return messages.get("subagent-started", id, Strings.nullToEmpty(description));
  }

  @Tool(
      name = "WaitForSubagent",
      description =
"""
Wait for a subagent to finish and read its answer. Wait for each subagent you started, one \
after another, before you finish your turn.
This blocks until the subagent finishes or until a while has passed, whichever comes first. If \
it is still working you are told so and told for how long, and you call this again to go on \
waiting — a subagent does not stop or lose anything because a wait for it came back. Keep \
waiting for as long as the work is worth; if it no longer is, call CancelSubagent instead.
""")
  public String waitForSubagent(
      @ToolParam(description = "The id StartSubagent returned") final String subagentId,
      final ToolContext context) {

    final var subRun = subagentOf(context, subagentId);
    if (subRun == null) {
      return messages.get("subagent-unknown", Strings.nullToEmpty(subagentId));
    }
    final var subagentConfig = appConfiguration.ai().tools().subagent();
    try {
      // Bounded, and this is the load-bearing part rather than a nicety. This call runs on a
      // Reactor boundedElastic worker, and so does the stream of every subagent: the pool is a
      // fixed number of single-threaded executors, a worker is pinned to one of them when it is
      // created, and once the pool is full a new worker is handed one that is already busy. A wait
      // that never let go could therefore be holding the one thread the subagent it waits for
      // needs in order to finish — and neither would ever move again, with nothing thrown and
      // nothing logged. Letting go on a timer means the thread is always given back, so that
      // cannot happen; what it costs is a further model call whenever a subagent outlives one
      // poll.
      if (!subRun.done.await(subagentConfig.waitPoll().toMillis(), TimeUnit.MILLISECONDS)) {
        final var waited = subRun.age();
        if (waited.compareTo(subagentConfig.waitTimeout()) < 0) {
          log.info(
              "Subagent {} still running after {}, handing the turn back to the model",
              subagentId,
              waited);
          return messages.get(
              "subagent-still-running",
              subagentId,
              Strings.nullToEmpty(subRun.description),
              humanize(waited));
        }
        // Past the ceiling this is a fault, not slow work: something is holding the run open that
        // nothing else is going to release. Said out loud, because the whole point of the ceiling
        // is that a turn never again hangs for good with nothing in the log to say why.
        log.error(
            "Subagent {} did not finish within {}; cancelling it",
            subagentId,
            subagentConfig.waitTimeout());
        springAgent.cancel(subagentId);
        return messages.get(
            "subagent-wait-timed-out", subagentId, humanize(subagentConfig.waitTimeout()));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return messages.get("subagent-interrupted", subagentId);
    }
    return switch (subRun.outcome) {
      case COMPLETED ->
          subRun.content.isBlank()
              ? messages.get("subagent-no-answer", subagentId)
              : messages.get("subagent-answer", subagentId, Strings.nullToEmpty(subRun.description))
                  + "\n\n"
                  + subRun.content;
      case CANCELLED -> messages.get("subagent-cancelled", subagentId);
      case FAILED ->
          messages.get(
              "subagent-failed",
              subagentId,
              subRun.error == null
                  ? "unknown"
                  : Strings.nullToEmpty(subRun.error.getMessage()).isEmpty()
                      ? subRun.error.getClass().getSimpleName()
                      : subRun.error.getMessage());
    };
  }

  @Tool(
      name = "CancelSubagent",
      description =
"""
Stop a subagent you no longer need the answer from, so it does not keep working and spending \
tokens on it. What it had already done is lost; there is no answer to collect afterwards. A \
subagent that has already finished is left alone.
""")
  public String cancelSubagent(
      @ToolParam(description = "The id StartSubagent returned") final String subagentId,
      final ToolContext context) {

    final var subRun = subagentOf(context, subagentId);
    if (subRun == null) {
      return messages.get("subagent-unknown", Strings.nullToEmpty(subagentId));
    }
    if (!subRun.running()) {
      return messages.get("subagent-already-finished", subagentId, subRun.outcome.name());
    }
    // Cooperative, as every cancel here is: the run stops at its next emission. Its own listener
    // still reports it finished, so a wait already outstanding on it comes back rather than
    // hanging.
    springAgent.cancel(subagentId);
    return messages.get("subagent-cancel-requested", subagentId);
  }

  /** A duration as the model should read it back to the user, rather than as {@code PT1M30S}. */
  private static String humanize(final Duration duration) {
    final var seconds = duration.toSeconds();
    return seconds < 60 ? seconds + "s" : duration.toMinutes() + "m";
  }

  /** The subagent of the calling run under that id, or null — including one started by a run. */
  private SubRun subagentOf(final ToolContext context, final String subagentId) {
    final var parentRequestId = ToolContexts.require(context, ToolContexts.REQUEST_ID);
    if (Strings.isNullOrEmpty(subagentId)) {
      return null;
    }
    // Only its own: an id belongs to the run that started it, so one conversation cannot read or
    // stop another conversation's work by naming it.
    return subagentsByRun.getOrDefault(parentRequestId, new ConcurrentHashMap<>()).get(subagentId);
  }

  /**
   * The configured template over the one variable a subagent has, the brief it was given. Kept off
   * the happy path of a blown-up template, as a scheduled task firing is: a subagent that cannot be
   * introduced is still worth running, so its brief goes to the model unwrapped.
   */
  private String subagentPrompt(final String prompt) {
    final var template = appConfiguration.ai().subagentPrompt();
    try {
      return PromptTemplate.builder()
          .template(template)
          .variables(Map.of("taskText", prompt))
          .build()
          .render();
    } catch (Exception e) {
      log.error("Failed to render app.ai.subagent-prompt, sending the brief as-is", e);
      return prompt;
    }
  }
}
