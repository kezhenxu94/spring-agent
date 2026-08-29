package me.kezhenxu94.springagent.events.situation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.dao.models.ObservedEvent;
import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.core.dao.repo.ObservedEventRepo;
import me.kezhenxu94.springagent.events.config.EventsMessages;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import org.springframework.stereotype.Component;

/**
 * Renders a situation as the text a triage run is given: what this is, how much of it there has
 * been, what the agent itself concluded last time, and the most recent observations as evidence.
 *
 * <p>This is what stands in for a conversation history, and the reason there is not one. A
 * situation looked at twenty times would otherwise carry a transcript of twenty turns, which grows
 * without bound and is then trimmed from the front — dropping the earliest evidence, the part that
 * says when this began. A rendering is bounded, reproducible, and can be read in the database by
 * somebody wondering what the agent was told.
 *
 * <p>Every word this adds is the agent's own and comes from {@link EventsMessages}, so a workspace
 * that speaks Chinese is briefed in Chinese. What is quoted between the fences is not: those are
 * whoever caused the events' words, reproduced exactly, and translating evidence would be inventing
 * it.
 *
 * <p>The fence is not decoration. It plus the sentence on it is what marks where our words stop and
 * a stranger's begin, which is the whole defence against an issue body written to give the agent
 * instructions.
 */
@Component
@RequiredArgsConstructor
public class SituationBrief {

  private final ObservedEventRepo events;
  private final EventsProperties properties;
  private final EventsMessages messages;
  private final Clock clock;

  public String render(final Situation situation) {
    final var now = clock.instant();
    final var lines = new ArrayList<String>();

    lines.add(messages.get("brief-headline", situation.id(), situation.source()));
    lines.add(messages.get("brief-what", messages.unknown(situation.title())));
    if (situation.chatId() != null && !situation.chatId().isBlank()) {
      lines.add(messages.get("brief-chat", situation.chatId()));
    }
    lines.add(observed(situation, now));
    lines.add(looks(situation, now));
    assessment(situation).ifPresent(lines::addAll);
    lines.addAll(evidence(situation, now));

    return String.join("\n", lines);
  }

  private String observed(final Situation situation, final Instant now) {
    final var count = situation.eventCount() == null ? 0 : situation.eventCount();
    if (situation.firstSeenAt() == null || situation.lastEventAt() == null) {
      return messages.get("brief-observations", count);
    }
    return messages.get(
        "brief-observations-seen",
        count,
        messages.ago(situation.firstSeenAt(), now),
        messages.ago(situation.lastEventAt(), now));
  }

  private String looks(final Situation situation, final Instant now) {
    // The generation is the number of the attempt now under way, so the number of completed looks
    // is one fewer.
    final var before = (situation.generation() == null ? 0 : situation.generation()) - 1;
    if (before < 1) {
      return messages.get("brief-first-look");
    }
    if (situation.lastEvaluatedAt() == null) {
      return messages.get("brief-later-look-undated", before);
    }
    return messages.get("brief-later-look", before, messages.ago(situation.lastEvaluatedAt(), now));
  }

  private java.util.Optional<List<String>> assessment(final Situation situation) {
    if (situation.assessment() == null || situation.assessment().isBlank()) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(
        List.of(
            "",
            messages.get(
                "brief-assessment",
                situation.decision() == null
                    ? messages.get("brief-unknown")
                    : situation.decision().name(),
                messages.unknown(situation.severity()),
                situation.confidence() == null
                    ? messages.get("brief-unknown")
                    : String.valueOf(situation.confidence())),
            situation.assessment()));
  }

  private List<String> evidence(final Situation situation, final Instant now) {
    final var all = events.findBySituationId(situation.id());
    final var shown = mostRecent(all, properties.maxEvidence());
    final var count = situation.eventCount() == null ? 0 : situation.eventCount();
    final var withheld = shown.size() < all.size() || count > properties.maxEventsPerSituation();

    final var lines = new ArrayList<String>();
    lines.add("");
    lines.add(
        messages.get(withheld ? "brief-evidence-more" : "brief-evidence", shown.size(), count));
    lines.add(messages.get("brief-fence-begin"));
    for (final var event : shown) {
      lines.add(
          messages.get(
              "brief-observation",
              event.observedAt() == null
                  ? messages.get("brief-unknown")
                  : messages.ago(event.observedAt(), now),
              messages.unknown(event.kind()),
              messages.unknown(event.summary())));
    }
    lines.add(messages.get("brief-fence-end"));
    return lines;
  }

  /**
   * The tail of the list, sorted here rather than by the repository.
   *
   * <p>{@code findBySituationId} is unsorted and unpaged, because a derived query carrying a {@code
   * Sort} is not something the Redis backend can serve and a contract two of three backends satisfy
   * is not a contract. Sorting in memory is affordable exactly because {@code
   * max-events-per-situation} bounds how many rows there can be.
   */
  private static List<ObservedEvent> mostRecent(final List<ObservedEvent> all, final int limit) {
    final var sorted =
        all.stream()
            .sorted(
                Comparator.comparing(
                    (ObservedEvent e) -> e.observedAt() == null ? Instant.EPOCH : e.observedAt()))
            .toList();
    return sorted.size() <= limit ? sorted : sorted.subList(sorted.size() - limit, sorted.size());
  }
}
