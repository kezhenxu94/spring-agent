package me.kezhenxu94.springagent.events.situation;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.observing.Actor;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import org.springframework.stereotype.Component;

/**
 * Whose events a source is willing to be told about, compiled once from what the deployment wrote.
 *
 * <p>A class of its own for the reason {@link PlaybookFilters} is one, and built the same way:
 * {@code EventsProperties.Policy} resolves the layers of configuration and stops there, and turning
 * a resolved value into the thing the module uses — a parsed expression, a compiled pattern — is a
 * separate job with its own failure modes.
 *
 * <p><b>What this is actually for.</b> Everything an observation carries was written by whoever
 * caused it, and the module's answer to that has so far been framing: {@code SituationBrief} fences
 * the evidence and every triage prompt says at length that the quoted text is data and never
 * instructions. That raises the cost of an attack and is worth keeping, but it is not a boundary —
 * a model has no privilege separation between the parts of what it is shown, so no amount of
 * fencing makes obedience impossible. The one variable that can be closed is who is able to put
 * text in front of it at all, and this is where that is decided.
 *
 * <p><b>Compiled at startup, and a bad pattern refuses to start.</b> A pattern is only ever applied
 * on the path an event arrives on, and one that failed to compile there would drop every event from
 * the source it was written for — silence indistinguishable from a quiet week. The same reasoning
 * as {@link PlaybookFilters} parsing its expressions at startup rather than at three in the
 * morning.
 *
 * <p><b>Only as strong as the authentication under it.</b> This compares against {@link
 * Actor#authenticatedName()} and never against {@link Actor#name()}: a name a source merely read
 * off a payload would let the attacker write both sides of the comparison, which is worse than
 * nothing, having converted "obviously untrusted" into "trusted". Whether a name is one or the
 * other is the source's to say honestly, and is written down on {@link Actor} where a source author
 * will meet it.
 */
@Slf4j
@Component
public class TrustedActors {

  /**
   * Longer than the longest address anybody can be reached at, and a refusal past it.
   *
   * <p>The patterns are the operator's and so are trusted, but what they are run over is not: a
   * payload chooses the subject string, and a pattern nobody thought was dangerous can take time
   * exponential in that string's length. 320 is the maximum length of an email address (RFC 3696),
   * which is the longest identity any source here reports and comfortably longer than a GitHub
   * login. Nothing legitimate is refused by this and an unbounded one cannot be offered.
   */
  private static final int MAX_ACTOR_LENGTH = 320;

  private final EventsProperties properties;

  /**
   * Source to its compiled patterns, holding an entry only for a source that configured some.
   * Populated once and read on every event thereafter, so it is built before anything can read it
   * and never written again.
   */
  private final Map<String, List<Pattern>> patterns = new HashMap<>();

  /**
   * The sources already complained about for reporting no actor, so the complaint is made once
   * rather than once per event.
   *
   * <p>Concurrent because unlike {@link #patterns} this is written on the event path, from whatever
   * thread a transport reports on.
   */
  private final Set<String> warned = ConcurrentHashMap.newKeySet();

  public TrustedActors(final EventsProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void compileAll() {
    properties
        .sources()
        .keySet()
        .forEach(
            source ->
                properties
                    .policyFor(source)
                    .map(EventsProperties.Policy::trustedActors)
                    .filter(actors -> actors != null && !actors.isEmpty())
                    .ifPresent(actors -> patterns.put(source, compile(source, actors))));
    if (!patterns.isEmpty()) {
      log.info("Only trusted actors will be heard from {}", patterns.keySet());
    }
  }

  /**
   * Whether {@code source} is willing to be told about something {@code actor} caused.
   *
   * <p>Answered for every observation before it is recorded, so it must be cheap and must not
   * throw.
   *
   * @param actor who the observation says caused it, or null where it names nobody. An actor
   *     nothing vouched for is refused as surely as a name that matched no pattern — {@link
   *     Actor#authenticatedName()} is the only thing this compares against.
   */
  public boolean trusts(final String source, final Actor actor) {
    final var configured = source == null ? null : patterns.get(source);
    if (configured == null) {
      // Everyone, and deliberately. See EventsProperties.Source#trustedActors for why the
      // permissive default is the safe one to ship, and SituationSweeper for what says so at
      // startup.
      return true;
    }
    if (actor == null) {
      // A source that authenticates nobody, asked to admit only certain people, can admit none of
      // them — the honest answer, and a total silence that has to announce itself. Grafana is the
      // case: it is machine-generated and has no actor to report, so a list configured for it drops
      // everything. Not caught at startup because whether a source can report an actor is not
      // knowable from configuration; said once here instead, where it is.
      if (warned.add(source)) {
        log.warn(
            "{} reports no actor at all, so app.events.sources.{}.trusted-actors rejects everything"
                + " it sends. Remove the setting if this source is trusted whole.",
            source,
            source);
      }
      return false;
    }
    final var name = actor.authenticatedName();
    if (name == null) {
      // Not warned about once per source, unlike the case above: a source that can authenticate
      // reports plenty of both, and this one is a fact about the event rather than about the
      // deployment's configuration. What was claimed is on the line because it is the whole of what
      // a reader wants — mail from a stranger is the case this exists for.
      log.debug("{} vouches for nobody as the sender of this one, which claims {}", source, actor);
      return false;
    }
    if (name.length() > MAX_ACTOR_LENGTH) {
      log.warn("Ignoring an implausibly long actor from {}: {} characters", source, name.length());
      return false;
    }
    // matches() and never find(): a pattern has to account for the whole name. Under find() the
    // obvious way to write an allow-list — the colleague's login, unanchored — would also admit
    // anybody who registered a name with it somewhere inside, which is an allow-list that admits
    // whoever thinks to ask.
    return configured.stream().anyMatch(pattern -> pattern.matcher(name).matches());
  }

  private List<Pattern> compile(final String source, final List<String> actors) {
    return actors.stream().map(actor -> compile(source, actor)).toList();
  }

  private Pattern compile(final String source, final String actor) {
    try {
      // Case-insensitive because the identities this is compared against are: GitHub folds the case
      // of a login and a domain name is defined case-insensitively, so a deployment writing out its
      // own colleagues' names would otherwise be one capital letter away from locking them out.
      return Pattern.compile(actor, Pattern.CASE_INSENSITIVE);
    } catch (PatternSyntaxException e) {
      throw new IllegalStateException(
          "app.events.sources." + source + ".trusted-actors is not a valid pattern: " + actor, e);
    }
  }
}
