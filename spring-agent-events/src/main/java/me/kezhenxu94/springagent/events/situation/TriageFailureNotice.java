package me.kezhenxu94.springagent.events.situation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.core.dao.repo.ObservedEventRepo;
import me.kezhenxu94.springagent.core.notify.Notifier;
import me.kezhenxu94.springagent.events.config.EventsMessages;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import org.springframework.stereotype.Component;

/**
 * What a person is told when a triage run failed: which situation, how much of it there has been,
 * what the last successful look concluded, whether anything will look at it again, and the most
 * recent observations.
 *
 * <p>The counterpart of {@link SituationBrief}, and a class of its own rather than a second method
 * on it, because the two share a subject and nothing else. The brief is read by the model: second
 * person, and a fence whose sentence exists to stop an issue body being followed as instructions.
 * This is read by whoever is on call: third person, and a fence that exists to mark where somebody
 * else's words begin. Two readers and two threats in one class would be a class nobody may safely
 * reword — tightening the model's fence would be editing a chat message.
 *
 * <p>What it says is chosen by the one question an operator has when this arrives: is somebody
 * needed, or does this sort itself out. That is the {@code next} phase, and it is passed in rather
 * than read off the situation because the notice is deliberately sent <em>before</em> the row is
 * written — see {@code SituationSweeper.report}.
 *
 * <p><b>Every word here that this deployment did not write goes through {@link Notifier#quoted}</b>
 * — the title, the observation kinds and summaries, and the error text. That last one is not
 * obvious and is the reason this takes a whole {@code Notifier} rather than an escaping function: a
 * gateway that refuses a request routinely echoes it back in the response body, and the request is
 * the brief, so an exception message can carry an alert body that carries {@code <at id=all></at>}.
 * Unescaped, a stranger could notify a whole group through this bot by writing the right thing in
 * an issue title.
 *
 * <p>How many observations are quoted is {@code app.events.max-evidence} — the same number that
 * bounds what a run is shown, because it is the same question asked about the same rows. Note what
 * that costs: no {@link Notifier} truncates, so at the far end of that setting this produces a
 * message a chat platform refuses, and the refusal arrives as an exception rather than as a shorter
 * notice. The arithmetic is {@code max-evidence} times the 1024 characters {@code
 * ObservedEvent.summary} stores — some 20KB at the shipped default of 20, which both surfaces
 * accept. A deployment that raises it far past that trades the notice for a log line.
 */
@Component
@RequiredArgsConstructor
public class TriageFailureNotice {

  private final ObservedEventRepo events;
  private final EventsProperties properties;
  private final EventsMessages messages;
  private final Clock clock;

  /**
   * @param next the phase the situation is about to be written with, which is the whole of whether
   *     anything will look at it again
   * @param error what {@code SituationSweeper.describe} made of the failure
   * @param notifier the surface this is going to, for its escaping dialect and nothing else
   */
  public String render(
      final Situation situation,
      final Situation.Phase next,
      final String error,
      final Notifier notifier) {
    final var now = clock.instant();
    final var lines = new ArrayList<String>();

    // The headline is the message this module has always sent: the same four values in the same
    // words, so a deployment upgrading recognises what arrives. Everything below it is what was
    // missing.
    //
    // The error keeps its own lines, unlike everything else quoted here, because it is the one
    // thing that lands in a code block: a stack trace or a gateway's JSON body is what it usually
    // is, and collapsing that onto one line is what would make it unreadable. A newline does not
    // end a fenced block — only a line of its own made of backticks does, and every surface here
    // either escapes a backtick or renders a broken fence as cosmetically odd text rather than as
    // anything that acts.
    lines.add(
        messages.get(
            "triage-failed",
            situation.source(),
            situation.id(),
            notifier.quoted(oneLine(situation.title())),
            notifier.quoted(messages.unknown(error))));

    lines.add("");
    lines.add(attempt(situation, now));
    assessment(situation).ifPresent(lines::add);
    if (situation.chatId() != null && !situation.chatId().isBlank()) {
      lines.add(messages.get("notice-chat", situation.chatId()));
    }
    lines.add(
        messages.get(
            next == Situation.Phase.AWAITING_EVALUATION ? "notice-next-due" : "notice-next-quiet"));
    lines.addAll(observations(situation, now, notifier));

    return String.join("\n", lines);
  }

  /**
   * Which attempt this was, and how much has been observed.
   *
   * <p>The generation itself, not one fewer as {@link SituationBrief} reports it: the brief counts
   * the looks that came <em>before</em> the one now running, and the attempt this is about is the
   * one that just failed.
   *
   * <p>Both counts are handed over as strings for the reason {@code EventsMessages.ago} gives: as
   * numbers, {@code MessageFormat} groups them by locale and a long outage reads as {@code 1,000}.
   */
  private String attempt(final Situation situation, final Instant now) {
    final var generation =
        String.valueOf(situation.generation() == null ? 0 : situation.generation());
    final var count = String.valueOf(situation.eventCount() == null ? 0 : situation.eventCount());
    if (situation.firstSeenAt() == null || situation.lastEventAt() == null) {
      return messages.get("notice-attempt-undated", generation, count);
    }
    return messages.get(
        "notice-attempt",
        generation,
        count,
        messages.ago(situation.firstSeenAt(), now),
        messages.ago(situation.lastEventAt(), now));
  }

  /**
   * What the last look that worked concluded, where there was one.
   *
   * <p>The three fields and not the assessment prose. This is a chat message read to decide whether
   * to go and look; the paragraph the model wrote is unbounded and is in the situation for whoever
   * does. Gated on the prose being there all the same, as the brief gates it: the fields alone,
   * with no assessment behind them, are a run that recorded a decision and said nothing.
   */
  private Optional<String> assessment(final Situation situation) {
    if (situation.assessment() == null || situation.assessment().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        messages.get(
            "notice-assessment",
            situation.decision() == null ? messages.unknown(null) : situation.decision().name(),
            messages.unknown(situation.severity()),
            situation.confidence() == null
                ? messages.unknown(null)
                : String.valueOf(situation.confidence())));
  }

  /**
   * The evidence, quoted between a fence.
   *
   * <p>Nothing at all where there is none, rather than a header over an empty fence: a situation
   * whose observations have been swept away is not worth three lines saying so in a message about
   * something else.
   *
   * <p>The bullet is {@code brief-observation}, shared with the brief. It is the only string in
   * that family with no reader in it — a time, a kind and a summary — so the two may have it in
   * common without the brief's voice coming with it.
   */
  private List<String> observations(
      final Situation situation, final Instant now, final Notifier notifier) {
    final var all = events.findBySituationId(situation.id());
    if (all.isEmpty()) {
      return List.of();
    }
    final var shown = SituationBrief.mostRecent(all, properties.maxEvidence());
    final var count = situation.eventCount() == null ? shown.size() : situation.eventCount();

    final var lines = new ArrayList<String>();
    lines.add("");
    lines.add(messages.get("notice-evidence", String.valueOf(shown.size()), String.valueOf(count)));
    lines.add(messages.get("notice-fence-begin"));
    for (final var event : shown) {
      lines.add(
          messages.get(
              "brief-observation",
              event.observedAt() == null
                  ? messages.unknown(null)
                  : messages.ago(event.observedAt(), now),
              notifier.quoted(oneLine(event.kind())),
              notifier.quoted(oneLine(event.summary()))));
    }

    // A blank line before the closing fence, which the brief does not need: this one is rendered as
    // markdown by a chat surface, and a line following a bullet with no blank line between them is
    // read as a continuation of that bullet. Without it the marker saying where somebody else's
    // words stop is drawn inside the last of them, which is the one place it must not be.
    lines.add("");
    lines.add(messages.get("notice-fence-end"));
    return lines;
  }

  /**
   * Somebody else's words on one line, and never blank.
   *
   * <p>Collapsed rather than cut. How much is quoted is {@code max-evidence}'s decision, but a
   * summary is one line in a bulleted list and a title is inline in a sentence, so a payload's own
   * newlines would break out of both — leaving half the notice loose in the chat with no bullet and
   * nothing around it saying whose words those are. The error is the exception, and is not put
   * through this: see {@link #render}. Whitespace is flattened before the text is escaped, never
   * after: escaping is what makes a stranger's markup inert, and rewriting it afterwards is how
   * that gets undone by accident.
   */
  private String oneLine(final String text) {
    return messages.unknown(text == null ? null : text.replaceAll("\\s+", " ").strip());
  }
}
