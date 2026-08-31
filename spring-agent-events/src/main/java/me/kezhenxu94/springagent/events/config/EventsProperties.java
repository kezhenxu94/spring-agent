package me.kezhenxu94.springagent.events.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.observing.Route;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Everything this module needs told to it, and the one place each of these values is written down.
 *
 * <p>The constants below are the defaults, and {@link EventsDefaults} contributes them to the
 * environment so that an application which merely puts this module on its classpath behaves like
 * the server in this repository rather than like whatever the binder left. The compact constructor
 * applies them again, which is not the duplication {@code ToolDefaults} warns about: both read the
 * same constant, so there is one value with two readers, and a properties object built directly in
 * a test is as complete as one Boot bound.
 *
 * <p>Settings come in two layers. The top level is what applies unless something says otherwise,
 * and {@code sources.<name>} overrides it per source, because the sources genuinely want different
 * policies: an alert should be left to settle for half a minute, while a group chat wants long
 * enough for the people in it to answer each other first and a much longer cooldown afterwards, so
 * that the agent does not become a presence in a conversation it was not invited to. {@link
 * #policyFor(String)} resolves the two layers plus the built-in per-source defaults into the one
 * record the rest of the module reads.
 *
 * <p>A source not named in {@code sources} is not configured, and observations from it are dropped.
 * That is deliberately explicit: a receiver that accepted whatever arrived would be a receiver
 * nobody decided to run.
 *
 * @param enabled whether any of this runs at all. Off by default, for the reason {@code
 *     app.ai.tools.shell.type} defaults to {@code none}: a half-configured receiver should do
 *     nothing rather than accept traffic nobody has secured.
 * @param sweepInterval how often to look for situations owed an evaluation. The floor on how late
 *     an evaluation can be, so it is short; the work per sweep is one indexed read.
 * @param maxConcurrentEvaluations how many triage runs may be in flight at once, across every
 *     source. The only backpressure there is — a storm becomes a queue rather than a bill.
 * @param maxEventsPerSituation how many observations to keep per situation. Past this they are
 *     counted and not stored: the count is what the agent reasons about at that scale, and the
 *     thousandth alert body is not evidence anybody reads.
 * @param maxEvidence how many recent observations to put in the prompt. The rest stay one tool call
 *     away, which is the point of {@code GetSituationEvents}.
 * @param maxBodySize the largest webhook body accepted, before anything parses it
 * @param debounce how long a situation must be quiet before it is worth an opinion. Every arriving
 *     observation pushes the deadline out, which is what turns a thousand alerts into one run.
 * @param maxDebounce the cap on that, measured from the first observation not yet evaluated.
 *     Without it a source emitting steadily would defer its evaluation for ever.
 * @param cooldown the least time between two evaluations of one situation, whatever arrives in
 *     between. What stops a busy situation from being re-read every debounce.
 * @param resolveAfterQuiet how long an open situation may go unobserved before it is closed on the
 *     grounds that whatever it was has stopped. Also what keeps the open set — and so every sweep —
 *     small.
 * @param resolveAfterEvaluation whether one evaluation ends the situation. False suits a condition
 *     that persists and wants watching; true suits a window over a stream, where the next batch of
 *     messages is a new question rather than more of the old one.
 * @param playbook how a triage run finds the deployment's own instructions for dealing with an
 *     event, overriding every source at once. Usually left unset here and stated per source, since
 *     the whole point is that a GitHub event and an alert are dealt with differently.
 * @param triagePrompt what the agent is told it is doing, overriding every source at once. Null
 *     unless a deployment says so, and then each source is given its own file by {@code
 *     TriagePrompts} — which is where the shipped wording lives, and where the framing of observed
 *     text as untrusted lives with it. Read that before replacing this: a prompt that drops the
 *     framing hands whoever can open an issue a prompt of their own.
 * @param sources per-source settings, keyed by {@link
 *     me.kezhenxu94.springagent.core.observing.Observation#source()}
 */
@lombok.Builder
@ConfigurationProperties(prefix = EventsProperties.PREFIX)
public record EventsProperties(
    boolean enabled,
    Duration sweepInterval,
    int maxConcurrentEvaluations,
    int maxEventsPerSituation,
    int maxEvidence,
    DataSize maxBodySize,
    Duration debounce,
    Duration maxDebounce,
    Duration cooldown,
    Duration resolveAfterQuiet,
    boolean resolveAfterEvaluation,
    Playbook playbook,
    String triagePrompt,
    Map<String, Source> sources) {

  public static final String PREFIX = "app.events";

  public static final boolean DEFAULT_ENABLED = false;
  public static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofSeconds(5);
  public static final int DEFAULT_MAX_CONCURRENT_EVALUATIONS = 2;
  public static final int DEFAULT_MAX_EVENTS_PER_SITUATION = 200;
  public static final int DEFAULT_MAX_EVIDENCE = 20;
  public static final DataSize DEFAULT_MAX_BODY_SIZE = DataSize.ofMegabytes(1);
  public static final Duration DEFAULT_DEBOUNCE = Duration.ofSeconds(30);
  public static final Duration DEFAULT_MAX_DEBOUNCE = Duration.ofMinutes(5);
  public static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(10);
  public static final Duration DEFAULT_RESOLVE_AFTER_QUIET = Duration.ofHours(6);
  public static final boolean DEFAULT_RESOLVE_AFTER_EVALUATION = false;

  /**
   * The source name the Feishu integration reports group chat messages under.
   *
   * <p>The same literal appears in the Feishu integration, which cannot depend on this module and
   * so cannot share the constant. A textual coupling of the kind this codebase already lives with —
   * {@code KnowledgeToolsConfiguration} names an auto-configuration class by string for the same
   * reason. Rename it in one place only and chat observation silently stops being configurable.
   */
  public static final String FEISHU_CHAT = "feishu-chat";

  /**
   * The source name the Slack integration reports channel messages under.
   *
   * <p>Textually coupled to {@code SlackChatObservations.SOURCE} for the same reason {@link
   * #FEISHU_CHAT} is, and with the same consequence for getting it wrong: rename it in one place
   * only and chat observation silently stops being configurable.
   */
  public static final String SLACK_CHAT = "slack-chat";

  /**
   * What a chat source is worth waiting for, and how rarely it is worth speaking up in.
   *
   * <p>Shared by {@link #FEISHU_CHAT} and {@link #SLACK_CHAT} because the question a watched room
   * asks is the same one whichever product the room is in — these are numbers about how people talk
   * to each other, not about a vendor's API.
   */
  private static Source chatDefaults() {
    return Source.builder()
        // Long enough that people get to answer each other first, and that a burst of
        // messages is read as one exchange rather than evaluated line by line.
        .debounce(Duration.ofSeconds(45))
        .maxDebounce(Duration.ofMinutes(2))
        // The strictest number in this file. Chiming in twice in half an hour is how a bot
        // that was occasionally useful becomes one everybody mutes.
        .cooldown(Duration.ofMinutes(30))
        // A window over a stream: once this exchange has been considered, the next messages
        // are a new question rather than more of this one.
        .resolveAfterEvaluation(true)
        .resolveAfterQuiet(Duration.ofHours(1))
        .build();
  }

  /** What a source with nothing of its own to say contributes to the merge: nothing. */
  private static final Source NOTHING_IN_PARTICULAR = Source.builder().build();

  /**
   * Defaults for the sources shipped with this module, applied under whatever the deployment says
   * and over the top-level settings.
   *
   * <p>In code rather than in the yaml because contributing them to the environment would create
   * the map entry, and a source present in {@code sources} is a source a deployment asked for.
   * These are how a shipped source differs from the general case, not whether it runs.
   *
   * <p>Timings only. What each source says to the model is a prompt file, resolved per source and
   * per language by {@code TriagePrompts}, because it is prose rather than a value.
   */
  static final Map<String, Source> BUILT_IN =
      Map.of(FEISHU_CHAT, chatDefaults(), SLACK_CHAT, chatDefaults());

  public EventsProperties {
    sweepInterval = sweepInterval == null ? DEFAULT_SWEEP_INTERVAL : sweepInterval;
    maxConcurrentEvaluations =
        maxConcurrentEvaluations <= 0
            ? DEFAULT_MAX_CONCURRENT_EVALUATIONS
            : maxConcurrentEvaluations;
    maxEventsPerSituation =
        maxEventsPerSituation <= 0 ? DEFAULT_MAX_EVENTS_PER_SITUATION : maxEventsPerSituation;
    maxEvidence = maxEvidence <= 0 ? DEFAULT_MAX_EVIDENCE : maxEvidence;
    maxBodySize = maxBodySize == null ? DEFAULT_MAX_BODY_SIZE : maxBodySize;
    debounce = debounce == null ? DEFAULT_DEBOUNCE : debounce;
    maxDebounce = maxDebounce == null ? DEFAULT_MAX_DEBOUNCE : maxDebounce;
    cooldown = cooldown == null ? DEFAULT_COOLDOWN : cooldown;
    resolveAfterQuiet = resolveAfterQuiet == null ? DEFAULT_RESOLVE_AFTER_QUIET : resolveAfterQuiet;
    playbook = playbook == null ? Playbook.NONE : playbook;
    triagePrompt = blankToNull(triagePrompt);
    sources = sources == null ? Map.of() : Map.copyOf(sources);
  }

  /**
   * The resolved settings for one source, or empty where the deployment has not configured it or
   * has turned it off.
   *
   * <p>Empty rather than a default policy, so that an unconfigured source is dropped at the door
   * instead of being quietly given the general one. A webhook path nobody set a secret for would
   * otherwise be an open endpoint.
   */
  public Optional<Policy> policyFor(final String source) {
    if (!enabled || source == null) {
      return Optional.empty();
    }
    final var configured = sources.get(source);
    if (configured == null || Boolean.FALSE.equals(configured.enabled())) {
      return Optional.empty();
    }
    // Merged against an empty source rather than against null, so each line below reads as the
    // three layers it is instead of as a null check with three layers hidden inside it.
    final var builtIn = BUILT_IN.getOrDefault(source, NOTHING_IN_PARTICULAR);
    return Optional.of(
        new Policy(
            source,
            pick(configured.secret(), builtIn.secret(), null),
            configured.ownerUserId(),
            pick(configured.route(), builtIn.route(), Route.NONE),
            pick(configured.playbook(), builtIn.playbook(), playbook),
            // No global layer, unlike every line around it. See Source#trustedActors.
            pick(emptyToNull(configured.trustedActors()), builtIn.trustedActors(), null),
            pick(configured.debounce(), builtIn.debounce(), debounce),
            pick(configured.maxDebounce(), builtIn.maxDebounce(), maxDebounce),
            pick(configured.cooldown(), builtIn.cooldown(), cooldown),
            pick(configured.resolveAfterQuiet(), builtIn.resolveAfterQuiet(), resolveAfterQuiet),
            pick(
                configured.resolveAfterEvaluation(),
                builtIn.resolveAfterEvaluation(),
                resolveAfterEvaluation),
            pick(blankToNull(configured.triagePrompt()), builtIn.triagePrompt(), triagePrompt)));
  }

  private static <T> T pick(final T configured, final T builtIn, final T global) {
    if (configured != null) {
      return configured;
    }
    return builtIn != null ? builtIn : global;
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * The list-valued {@link #blankToNull}, and needed for the same reason.
   *
   * <p>The binder hands back an empty list for a key written with nothing under it, and {@link
   * #pick} takes the first non-null — so without this, {@code trusted-actors:} with an empty value
   * would beat the layers beneath it and mean something entirely different from leaving it out. An
   * empty setting says nothing, whatever its type.
   */
  private static <T> List<T> emptyToNull(final List<T> value) {
    return value == null || value.isEmpty() ? null : List.copyOf(value);
  }

  /**
   * Per-source overrides. Every field is nullable and means "say nothing, take what applies" —
   * which is why none of them are defaulted in a compact constructor here, unlike the top level.
   *
   * @param enabled set false to keep a configured source's settings while turning it off
   * @param secret the shared secret or token the source authenticates with. What it means is the
   *     source's business: an HMAC key for GitHub, a token compared whole for GitLab and Grafana.
   * @param ownerUserId the identity a triage run for this source assumes, and the knowledge base
   *     its playbook is read from. Must be an identity of its own and not a person's — see {@code
   *     SituationTriageScenario} for what a run inherits from it.
   * @param route where to report that a triage run for this source *failed*, and nothing else. Not
   *     where the agent talks: a run that works reaches people the way its playbook says to, and an
   *     observation's own route — the chat a message came from — is never filled in from here.
   * @param playbook which of {@code ownerUserId}'s documents say how to deal with this source's
   *     events, and what to look them up with
   * @param trustedActors whose events this source is willing to be told about, as regular
   *     expressions matched against {@link
   *     me.kezhenxu94.springagent.core.observing.Observation#actor()}. Compiled at startup by
   *     {@code TrustedActors}, which refuses to start on one that will not parse, and read by
   *     {@code SituationEventIntake} before an observation is recorded.
   *     <p>Null — the default — means everyone, which is what keeps a deployment that upgrades into
   *     this working the way it did the day before. Not an oversight to be tightened later: an
   *     allow-list that arrived switched on would stop existing triage silently, and a source that
   *     quietly evaluates nothing is the failure this module's startup checks exist to avoid. What
   *     it costs is that the safe configuration has to be chosen, which is why {@code
   *     SituationSweeper} names every source that has not chosen it.
   *     <p>A private repository or an internal GitLab says so as {@code ['.*']} rather than by
   *     anything read from the payload. Trust inferred from a {@code repository.private} flag would
   *     move the decision into the body of the very request it governs, and a repository flipped to
   *     public would then open the door with no configuration change and no restart.
   *     <p>No top-level fallback, for the reason {@link Playbook} is overridden whole: patterns are
   *     written in one source's vocabulary — GitHub logins, email addresses — and applying a global
   *     list to a source it was never written for either admits the wrong people or, more likely,
   *     silently admits nobody.
   */
  @lombok.Builder
  public record Source(
      Boolean enabled,
      String secret,
      String ownerUserId,
      Route route,
      Playbook playbook,
      List<String> trustedActors,
      Duration debounce,
      Duration maxDebounce,
      Duration cooldown,
      Duration resolveAfterQuiet,
      Boolean resolveAfterEvaluation,
      String triagePrompt) {}

  /**
   * How a triage run finds what this deployment has written down about dealing with a source's
   * events.
   *
   * <p>Prose in the knowledge base rather than settings here, because what it has to say — what
   * matters, what to check first, who to tell, when to stay silent — is prose, and because it is
   * then editable by the people who know it without a deployment. This record is only the lookup.
   *
   * <p>The base looked in is always the one owned by the source's {@code ownerUserId} alone, never
   * the group or tenant an incoming event happens to name. That is not configurable, and the reason
   * is that these documents decide what the agent does about text an attacker can write.
   *
   * <p>Both fields are stated by a deployment and neither is guessed, so a source with no {@code
   * query} simply has no playbook and triages on the prompt alone.
   *
   * <p>Overridden whole, like {@code route} and unlike the timings: a source that states a playbook
   * states both halves of it. Merging field by field would let a global filter pinned to one
   * source's documents be applied to another source's query, which is a way of retrieving nothing
   * that reads, from the outside, exactly like a knowledge base with nothing in it.
   *
   * @param query what to retrieve the playbook with — a fixed question about the source, not the
   *     event. Retrieving against the event's own text would let whoever wrote it choose which of
   *     the deployment's documents the model is shown, and would match a runbook against a stack
   *     trace besides. Blank turns the playbook off for this source.
   * @param filter which documents count as this source's playbook, as a Spring AI filter expression
   *     over {@code KnowledgeMetadata} — e.g. {@code docId in ['runbook-github']}. Narrows what the
   *     owner may read and can never widen it. Parsed at startup by {@code PlaybookFilters}, which
   *     refuses to start on a malformed one. Blank means the whole of the owner's knowledge base,
   *     which is worth thinking twice about: see that class for why naming exact document ids is
   *     what keeps a run from writing its own playbook.
   */
  @lombok.Builder
  public record Playbook(String query, String filter) {

    public Playbook {
      query = blankToNull(query);
      filter = blankToNull(filter);
    }

    /** What a source that was configured with nothing has: no playbook. */
    public static final Playbook NONE = new Playbook(null, null);

    public boolean hasQuery() {
      return query != null;
    }
  }

  /**
   * One source's settings with every layer already applied — what the module reads, so that nothing
   * downstream has to know there were layers.
   */
  public record Policy(
      String source,
      String secret,
      String ownerUserId,
      Route route,
      Playbook playbook,
      List<String> trustedActors,
      Duration debounce,
      Duration maxDebounce,
      Duration cooldown,
      Duration resolveAfterQuiet,
      boolean resolveAfterEvaluation,
      String triagePrompt) {}
}
