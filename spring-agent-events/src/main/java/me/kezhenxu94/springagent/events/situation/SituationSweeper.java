package me.kezhenxu94.springagent.events.situation;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.core.dao.repo.SituationRepo;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeRetrieval;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeScope;
import me.kezhenxu94.springagent.core.notify.Notifier;
import me.kezhenxu94.springagent.events.config.EventsMessages;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import me.kezhenxu94.springagent.events.tools.SituationTools;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * The expensive half, and the part that decides how rarely to be expensive: finds the situations
 * that have settled, wakes the agent for them one at a time under a cap, and closes the ones
 * nothing has been heard about.
 *
 * <p>The shape is {@code ScheduledTaskService}'s, deliberately — a timer on the shared {@link
 * ThreadPoolTaskScheduler}, an {@code AgentRequest} built from a stored row, and a listener that
 * writes the outcome back. What differs is where the decision to run comes from: a scheduled task
 * fires because somebody said when, and a situation fires because it stopped changing.
 *
 * <p>Everything is driven from the database rather than from anything held in memory, which is what
 * makes a restart in the middle of a debounce a non-event and what lets two replicas share the
 * work. The one thing they must not both do is evaluate the same situation, and {@link
 * ProcessedMessageRepo#claim} is how that is settled — keyed by situation and attempt number, so
 * the first replica to claim an attempt owns it and the next attempt is a different key.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SituationSweeper {

  /** Stateless, so one instance serves every run. */
  private static final SituationTriageScenario SCENARIO = new SituationTriageScenario();

  private final SpringAgent springAgent;
  private final SituationRepo situations;
  private final ProcessedMessageRepo processedMessages;
  private final EventsProperties properties;

  /**
   * Asked once, at startup: a source whose owner is an administrator is a configuration this
   * refuses to run with. See {@link #start()}.
   */
  private final Admins admins;

  private final ThreadPoolTaskScheduler taskScheduler;
  private final SituationBrief brief;
  private final TriagePrompts triagePrompts;
  private final PlaybookFilters playbookFilters;

  /**
   * Where a triage failure is reported, if anywhere.
   *
   * <p>An {@link ObjectProvider} because core ships no {@link Notifier}: a deployment whose
   * surfaces implement none simply has nowhere to send these and the failure stays in the log,
   * which is what happened before there was anywhere to send it.
   */
  private final ObjectProvider<Notifier> notifier;

  /**
   * For the one line of this class a person reads. Everything else it writes goes to a log, which
   * stays in English here as it does everywhere else in this codebase.
   */
  private final EventsMessages messages;

  private final Clock clock;

  /**
   * How many triage runs are in flight, across every source. The only backpressure there is: a
   * storm of situations becomes a queue rather than a bill, and the sweep that finds them says so
   * in the log rather than starting them all.
   */
  private final AtomicInteger inFlight = new AtomicInteger();

  private volatile ScheduledFuture<?> sweep;

  @PostConstruct
  void start() {
    // Every configured source needs an identity of the agent's own to run as. Said at startup
    // rather than discovered when the first alert arrives at three in the morning, since the
    // symptom then is a situation that quietly never gets evaluated.
    properties
        .sources()
        .keySet()
        .forEach(
            source ->
                properties
                    .policyFor(source)
                    .filter(policy -> isBlank(policy.owner().userId()))
                    .ifPresent(
                        policy ->
                            log.error(
                                "app.events.sources.{}.owner.user-id is not set, so situations from"
                                    + " {} can never be evaluated. It must name an identity of the"
                                    + " agent's own, never a person's.",
                                source,
                                source)));

    // And every configured source should say whose events it will accept. Complained about rather
    // than refused, unlike the administrator check below: a deployment that upgrades into this
    // feature has no such setting, and refusing to start would take its whole triage down over a
    // configuration that was correct the day before. Loud, because the default is the permissive
    // one and nothing else will ever mention it — see EventsProperties.Source#trustedActors for
    // why shipping the strict default would be the worse mistake.
    properties
        .sources()
        .keySet()
        .forEach(
            source ->
                properties
                    .policyFor(source)
                    .filter(policy -> policy.trustedActors() == null)
                    .ifPresent(
                        policy ->
                            log.error(
                                "app.events.sources.{}.trusted-actors is not set, so anybody who"
                                    + " can reach {} can put text in front of the agent. Name the"
                                    + " actors this deployment expects, or ['.*'] to say that"
                                    + " everybody who can reach it is already trusted.",
                                source,
                                source)));

    // And that identity must not be an administrator. A triage run assumes it and then reads text
    // whoever caused the event wrote, so an admin owner hands that author the admin tools —
    // WritePlaybook among them, which would let an issue body rewrite the playbook every later
    // triage of that source is steered by. Self-perpetuating, unattended, and invisible.
    //
    // Refused rather than logged, and refused here rather than checked per run. Nothing inside a
    // run can tell this one apart from any other run by the same owner — that is what assuming an
    // identity means — so the only honest place to catch it is the configuration that pairs the
    // two, before anything has happened. An operator who wants an admin identity to report into
    // chats it is not in should give this source an identity of its own instead.
    final var adminOwners =
        properties.sources().keySet().stream()
            .flatMap(source -> properties.policyFor(source).stream())
            .filter(policy -> admins.isAdmin(policy.owner().userId()))
            .map(EventsProperties.Policy::source)
            .sorted()
            .toList();
    if (!adminOwners.isEmpty()) {
      throw new IllegalStateException(
          "The owner.user-id of these event sources is listed in app.ai.admins: "
              + String.join(", ", adminOwners)
              + ". A triage run assumes that identity and reads text whoever caused the event"
              + " wrote, so it would hand them the admin tools. Give the source an identity of its"
              + " own, or take it out of app.ai.admins.");
    }

    sweep = taskScheduler.scheduleWithFixedDelay(this::sweepQuietly, properties.sweepInterval());
    log.info(
        "Watching for situations to evaluate every {}, at most {} at a time",
        properties.sweepInterval(),
        properties.maxConcurrentEvaluations());
  }

  @PreDestroy
  void stop() {
    if (sweep != null) {
      sweep.cancel(false);
    }
  }

  /**
   * Catches everything, and has to.
   *
   * <p>A task scheduled with a fixed delay is not run again after it throws — the executor drops it
   * silently. So an exception escaping one sweep would not fail loudly, it would stop the feature
   * for the lifetime of the process while every situation sat there looking due.
   */
  void sweepQuietly() {
    try {
      sweep();
    } catch (Throwable t) {
      log.error("A sweep failed; the next one will try again", t);
    }
  }

  void sweep() {
    if (!springAgent.accepting()) {
      log.debug("Shutting down, skipping the sweep");
      return;
    }
    evaluateDue();
    resolveQuiet();
  }

  private void evaluateDue() {
    final var now = clock.instant();
    final var due =
        situations.findByPhase(Situation.Phase.AWAITING_EVALUATION).stream()
            .filter(situation -> situation.status() == Situation.Status.OPEN)
            .filter(
                situation ->
                    situation.evaluateAfter() == null || !situation.evaluateAfter().isAfter(now))
            // Longest overdue first, so a backlog drains in the order things became interesting
            // rather than in whatever order the index happened to return them.
            .sorted(
                Comparator.comparing(
                    situation ->
                        situation.evaluateAfter() == null
                            ? Instant.EPOCH
                            : situation.evaluateAfter()))
            .toList();

    for (final var situation : due) {
      if (inFlight.get() >= properties.maxConcurrentEvaluations()) {
        // Said out loud rather than dropped. A cap that silently discards work reads, from the
        // outside, exactly like a feature that does not work.
        log.info(
            "{} evaluations already running; {} situation(s) will wait for the next sweep",
            inFlight.get(),
            due.size() - due.indexOf(situation));
        return;
      }
      evaluate(situation, now);
    }
  }

  private void evaluate(final Situation situation, final Instant now) {
    final var policy = properties.policyFor(situation.source()).orElse(null);
    if (policy == null) {
      log.debug(
          "Leaving situation {} alone: its source {} is no longer configured",
          situation.id(),
          situation.source());
      return;
    }
    if (isBlank(policy.owner().userId())) {
      log.debug(
          "Leaving situation {} alone: {} has no owner.user-id",
          situation.id(),
          situation.source());
      return;
    }

    final var generation = (situation.generation() == null ? 0 : situation.generation()) + 1;
    final var attempt = "situation:" + situation.id() + "#" + generation;
    if (!processedMessages.claim(attempt)) {
      log.debug("Another replica is evaluating {}", attempt);
      return;
    }

    final var claimed =
        situations.save(
            situation.toBuilder()
                .phase(Situation.Phase.INVESTIGATING)
                .generation(generation)
                .lastEvaluatedAt(now)
                .lastError(null)
                .build());

    inFlight.incrementAndGet();
    log.info(
        "Evaluating situation {} ({}), attempt {}, {} observations",
        claimed.id(),
        claimed.title(),
        generation,
        claimed.eventCount());
    try {
      springAgent.fire(
          AgentRequest.builder()
              // The attempt, not the situation: two evaluations of one situation under the same key
              // would collide in the agent's live-run map, and the first to finish would remove the
              // other's entry. The INVESTIGATING phase is what normally prevents the overlap; this
              // is what makes the id honest even so.
              .requestId(attempt)
              .scenario(SCENARIO)
              .userId(policy.owner().userId())
              .chatId(claimed.chatId())
              .chatType(claimed.chatType())
              // The owner's own group and tenant where the source named them, and only failing
              // that the ones the situation carries. Both orders are defensible for a chat source,
              // where the situation's ids come from the surface and are as real as any; the
              // configured one wins because for every other source they come from an observation,
              // and an identity the deployment wrote down should not be widened by something a
              // webhook said. What they decide is the shared workspace the run may read and write.
              .groupId(orElse(policy.owner().groupId(), claimed.groupId()))
              .tenantId(orElse(policy.owner().tenantId(), claimed.tenantId()))
              // No chat memory is read or written in this scenario, so this only names the run;
              // the situation's own id is the most useful thing to name it after.
              .conversationId(claimed.id())
              // Unattended: no card, no progress, nothing to stop it with, and no way to ask
              // anybody anything. It reaches a person only through what it chooses to send.
              .background(true)
              .description(messages.get("run-description", claimed.source(), claimed.id()))
              .userMessage(spec -> spec.text(triagePrompt(policy, claimed)))
              .knowledgeRetrieval(playbookFor(policy))
              .toolContext(Map.of(SituationTools.KEY_SITUATION_ID, claimed.id()))
              .listener(new Evaluation(claimed.id(), generation, policy))
              .build());
    } catch (RuntimeException e) {
      // fire reports through listeners rather than throwing, so this is the unexpected path. Give
      // the slot back regardless: a leaked one is permanent, and enough of them stop the feature.
      inFlight.decrementAndGet();
      log.error("Could not start a triage run for situation {}", claimed.id(), e);
      situations.save(
          claimed.toBuilder().phase(Situation.Phase.MONITORING).lastError(describe(e)).build());
      report(policy, claimed, e);
    }
  }

  /**
   * Closes situations nothing has been heard about, which is both the answer to "is it over" and
   * what keeps every sweep cheap: the open set is what {@code findByPhase} and {@code findByStatus}
   * read, and without this it would only ever grow.
   */
  private void resolveQuiet() {
    final var now = clock.instant();
    for (final var situation : situations.findByStatus(Situation.Status.OPEN)) {
      if (situation.phase() == Situation.Phase.INVESTIGATING) {
        continue;
      }
      final var quiet =
          properties
              .policyFor(situation.source())
              .map(EventsProperties.Policy::resolveAfterQuiet)
              // A source that has been unconfigured still has situations, and they still have to be
              // tidied away, so the global setting applies to them.
              .orElse(properties.resolveAfterQuiet());
      final var since =
          situation.lastEventAt() == null ? situation.firstSeenAt() : situation.lastEventAt();
      if (since != null && since.plus(quiet).isBefore(now)) {
        log.info("Closing situation {}: nothing heard for {}", situation.id(), quiet);
        situations.save(
            situation.toBuilder()
                .status(Situation.Status.RESOLVED)
                .phase(Situation.Phase.MONITORING)
                .resolvedAt(now)
                .build());
      }
    }
  }

  /**
   * The source's prompt over the one variable it has, the situation as text.
   *
   * <p>Kept off the happy path of a blown-up template, as {@code ScheduledTaskService} does with
   * its own: a deployment whose prompt has a stray brace in it should still get a triage, so the
   * brief goes to the model unwrapped rather than the run failing.
   */
  private String triagePrompt(final EventsProperties.Policy policy, final Situation situation) {
    final var rendered = brief.render(situation);
    // Configuration first, and the source's own file where it said nothing — which is the usual
    // case, and the only one that can be in the workspace's language.
    final var template =
        policy.triagePrompt() == null
            ? triagePrompts.forSource(policy.source())
            : policy.triagePrompt();
    try {
      return PromptTemplate.builder()
          .template(template)
          .variables(Map.of("situation", rendered))
          .build()
          .render();
    } catch (Exception e) {
      log.error(
          "Could not render the triage prompt for {}; sending the situation on its own",
          policy.source(),
          e);
      return rendered;
    }
  }

  /**
   * The knowledge this run's automatic retrieval should look at: the source's playbook, or nothing
   * stated at all where the source configured none.
   *
   * <p>The scope is the owner's user and only that — never a group or a tenant, neither the ones
   * the situation carries nor the ones the owner was configured with. That is the point of stating
   * it here rather than letting the run derive it from its own identity, and it is worth being
   * exact about what it prevents: the group and tenant on a situation come from the observation, so
   * a surface that reported a tenant would otherwise decide which knowledge base an unattended run
   * reasons from, and the documents in it are the ones that say what the agent does about text
   * somebody else wrote. A configured {@code owner.group-id} is narrower than that but is left out
   * for the second reason in {@code EventsProperties.Owner}: {@code WritePlaybook} writes into this
   * scope, and a shared one is a playbook anybody in the group can author.
   *
   * <p>Returning null where there is no query, rather than a scope with nothing to look up, so that
   * a deployment which has not written a playbook gets the retrieval it had before this existed.
   */
  private KnowledgeRetrieval playbookFor(final EventsProperties.Policy policy) {
    if (!policy.playbook().hasQuery()) {
      return null;
    }
    return new KnowledgeRetrieval(
        new KnowledgeScope(policy.owner().userId(), "", ""),
        playbookFilters.forSource(policy.source()),
        policy.playbook().query());
  }

  /**
   * Tells whoever asked to be told that a triage run did not work.
   *
   * <p>Only a run that failed or never started, which is the one thing an unattended run cannot
   * report for itself: it is a background run, so no surface renders it, and without this its
   * failure exists only in a log nobody is reading at the time. A tool call that failed and the
   * agent carried on from is not this — that is the agent working.
   *
   * <p>Deliberately not rate limited. Per-situation {@code cooldown} and {@code
   * max-concurrent-evaluations} already bound how often this can happen, and a suppressor on top of
   * them would be a thing that hides an outage in exactly the circumstances it is describing. If
   * this ever becomes noisy, the noise is the report.
   */
  private void report(
      final EventsProperties.Policy policy, final Situation situation, final Throwable error) {
    final var route = policy.route();
    if (route == null || route.isEmpty()) {
      return;
    }
    final var target = notifier.getIfAvailable();
    if (target == null) {
      log.debug("No Notifier is installed, so the failure of {} is only logged", situation.id());
      return;
    }
    try {
      target.send(
          route,
          messages.get(
              "triage-failed",
              situation.source(),
              situation.id(),
              situation.title(),
              describe(error)));
    } catch (RuntimeException e) {
      // Never let this displace what it was reporting. A surface that cannot reach its own service
      // is a second failure, and the first one is already recorded on the situation.
      log.error("Could not report the failure of situation {}", situation.id(), e);
    }
  }

  /** How many runs this sweeper believes are in flight. For tests and for logging. */
  public int inFlight() {
    return inFlight.get();
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }

  private static String orElse(final String configured, final String observed) {
    return isBlank(configured) ? observed : configured;
  }

  private static String describe(final Throwable error) {
    final var message = error.getMessage();
    return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }

  /**
   * Writes back what became of one evaluation, and gives the slot up.
   *
   * <p>Not optional, and not merely for tidiness. A triage run is a background run, so nothing else
   * reports it anywhere — {@code FeishuCardListener} returns early for a run with no message to
   * reply onto, which is every run here. Without this a run that failed would leave no trace at
   * all.
   */
  @RequiredArgsConstructor
  private final class Evaluation implements AgentResponseListener {
    private final String situationId;
    private final int generation;
    private final EventsProperties.Policy policy;

    private volatile Throwable error;

    @Override
    public void onError(final Throwable error) {
      this.error = error;
    }

    @Override
    public void onFinished(final AgentOutcome outcome) {
      try {
        finish(outcome);
      } catch (RuntimeException e) {
        log.error("Could not record the outcome of situation {}", situationId, e);
      } finally {
        inFlight.decrementAndGet();
      }
    }

    private void finish(final AgentOutcome outcome) {
      log.info("Situation {} evaluated, outcome={}", situationId, outcome);
      final var current = situations.findById(situationId).orElse(null);
      if (current == null) {
        return;
      }
      if (!Objects.equals(current.generation(), generation)) {
        // A later attempt owns the row now. Writing our phase over it would either revive a
        // situation somebody closed or steal one that is being looked at.
        log.debug(
            "Not recording attempt {} of situation {}: it is now at attempt {}",
            generation,
            situationId,
            current.generation());
        return;
      }

      if (error != null) {
        report(policy, current, error);
      }

      final var now = clock.instant();
      final var observedDuringTheRun =
          current.lastEventAt() != null
              && (current.lastEvaluatedAt() == null
                  || current.lastEventAt().isAfter(current.lastEvaluatedAt()));
      final var updated = current.toBuilder().lastError(error == null ? null : describe(error));

      if (observedDuringTheRun) {
        // More arrived while we were thinking, so what we just concluded is already out of date.
        // Due again, with the pending run of observations starting now so that max-debounce is
        // measured from them rather than from the batch already considered.
        updated.phase(Situation.Phase.AWAITING_EVALUATION).awaitingSince(now);
      } else {
        updated.phase(Situation.Phase.MONITORING).awaitingSince(null);
        // Only a run that finished gets to end the situation. A failed one has concluded nothing,
        // and closing on its behalf would throw away the evidence with it.
        if (policy.resolveAfterEvaluation() && outcome == AgentOutcome.COMPLETED) {
          updated.status(Situation.Status.RESOLVED).resolvedAt(now);
        }
      }
      // A failed evaluation is not retried on a timer. The error is recorded, and the next
      // observation makes the situation due again — which is the difference between a transient
      // failure being retried and a permanent one being retried for ever.
      situations.save(updated.build());
    }
  }
}
