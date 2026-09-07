package me.kezhenxu94.springagent.events.situation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import me.kezhenxu94.springagent.core.dao.models.ObservedEvent;
import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.core.notify.Notifier;
import me.kezhenxu94.springagent.events.config.EventsMessages;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import me.kezhenxu94.springagent.events.support.InMemoryRepos;
import me.kezhenxu94.springagent.events.support.MutableClock;
import me.kezhenxu94.springagent.events.support.TestI18n;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What somebody on call is actually told when a triage run failed.
 *
 * <p>Pinned as text for the reason {@code SituationBriefTest} gives about the brief: the text is
 * the interface. A notice that quietly stopped saying whether anything will look at the situation
 * again would still be a valid notice, and would leave every reader of it guessing.
 */
class TriageFailureNoticeTest {

  private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

  private final InMemoryRepos repos = new InMemoryRepos();
  private final MutableClock clock = new MutableClock(NOW);
  private final Notifier notifier = mock(Notifier.class);

  /** A surface that escapes nothing, so a test reads the words rather than a dialect. */
  private Notifier passthrough() {
    when(notifier.quoted(anyString())).thenAnswer(call -> call.getArgument(0));
    return notifier;
  }

  private TriageFailureNotice notice() {
    return notice(TestI18n.english());
  }

  private TriageFailureNotice notice(final EventsMessages messages) {
    final var properties =
        EventsProperties.builder().enabled(true).maxEventsPerSituation(200).maxEvidence(3).build();
    return new TriageFailureNotice(repos.events, properties, messages, clock);
  }

  private Situation situation() {
    return repos.situations.save(
        Situation.builder()
            .id("sit1")
            .source("grafana")
            .correlationKey("grafana:pg-primary")
            .title("Postgres primary unreachable")
            .status(Situation.Status.OPEN)
            .phase(Situation.Phase.INVESTIGATING)
            .firstSeenAt(NOW.minus(Duration.ofHours(2)))
            .lastEventAt(NOW.minus(Duration.ofMinutes(3)))
            .eventCount(12)
            .generation(3)
            .build());
  }

  private void event(final String id, final Duration ago, final String summary) {
    repos.events.save(
        ObservedEvent.builder()
            .id(id)
            .situationId("sit1")
            .source("grafana")
            .kind("alerting")
            .summary(summary)
            .observedAt(NOW.minus(ago))
            .build());
  }

  @Test
  @DisplayName("it says which situation, which attempt, and how much has been observed")
  void shouldDescribeTheFailure() {
    final var rendered =
        notice()
            .render(
                situation(),
                Situation.Phase.MONITORING,
                "IllegalStateException: the model refused",
                passthrough());

    // The headline this module has always sent, unchanged.
    assertThat(rendered).contains("Triage failed").contains("grafana").contains("sit1");
    assertThat(rendered).contains("Postgres primary unreachable");
    assertThat(rendered).contains("the model refused");
    // And what was missing from it: which attempt, and whether this is one stray webhook or two
    // hours of alerts.
    assertThat(rendered).contains("Attempt 3").contains("12 observation(s)");
    assertThat(rendered).contains("first seen 2h0m ago").contains("most recent 3m ago");
  }

  @Test
  @DisplayName("it says whether anything will look at the situation again")
  void shouldSayWhatHappensNext() {
    // The one question whoever reads this at three in the morning has, and the reason the phase is
    // passed in rather than read off a row that has not been written yet.
    final var quiet =
        notice().render(situation(), Situation.Phase.MONITORING, "boom", passthrough());
    assertThat(quiet).contains("will not be looked at again until something else is observed");

    final var due =
        notice().render(situation(), Situation.Phase.AWAITING_EVALUATION, "boom", passthrough());
    assertThat(due).contains("due again").doesNotContain("will not be looked at again");
  }

  @Test
  @DisplayName("what the last look concluded comes with it, where there was one")
  void shouldCarryThePreviousAssessment() {
    final var assessed =
        repos.situations.save(
            situation().toBuilder()
                .decision(Situation.Decision.ESCALATED)
                .severity("critical")
                .confidence(0.8)
                .assessment("The primary has been down for an hour and nobody has acknowledged it.")
                .build());

    final var rendered =
        notice().render(assessed, Situation.Phase.MONITORING, "boom", passthrough());

    assertThat(rendered).contains("ESCALATED").contains("critical").contains("0.8");
    // The three fields and not the paragraph: this is a chat message read to decide whether to go
    // and look, and the paragraph is waiting in the situation for whoever does.
    assertThat(rendered).doesNotContain("nobody has acknowledged");
  }

  @Test
  @DisplayName("a situation that never had a conclusion says nothing about one")
  void shouldOmitTheAssessmentLineWhenThereIsNone() {
    final var rendered =
        notice().render(situation(), Situation.Phase.MONITORING, "boom", passthrough());

    assertThat(rendered).doesNotContain("What the last look concluded");
  }

  @Test
  @DisplayName("the chat a situation concerns is named only where it has one")
  void shouldNameTheChatOnlyWhenThereIsOne() {
    assertThat(notice().render(situation(), Situation.Phase.MONITORING, "boom", passthrough()))
        .doesNotContain("The chat it concerns");

    final var inAChat = repos.situations.save(situation().toBuilder().chatId("oc_ops").build());
    assertThat(notice().render(inAChat, Situation.Phase.MONITORING, "boom", passthrough()))
        .contains("The chat it concerns: oc_ops");
  }

  @Test
  @DisplayName("the most recent observations come with it, oldest first and no more than asked for")
  void shouldQuoteTheMostRecentObservations() {
    event("e1", Duration.ofMinutes(30), "oldest, and not shown");
    event("e2", Duration.ofMinutes(9), "pg-primary-down firing");
    event("e3", Duration.ofMinutes(6), "pg-replica-lag firing");
    event("e4", Duration.ofMinutes(3), "pg-replica-lag resolved");

    final var rendered =
        notice().render(situation(), Situation.Phase.MONITORING, "boom", passthrough());

    // max-evidence is 3 here, and the count beside it is the situation's own — what is shown of
    // what there has been.
    assertThat(rendered).contains("Most recent observations, 3 of 12:");
    assertThat(rendered).doesNotContain("oldest, and not shown");
    assertThat(rendered.indexOf("pg-primary-down")).isLessThan(rendered.indexOf("resolved"));
    assertThat(rendered).contains("9m ago, alerting: pg-primary-down firing");
    // Inside the fence, which is what marks where the agent's words stop.
    final var start = rendered.indexOf("observed content, written by others");
    final var end = rendered.indexOf("end observed content");
    assertThat(rendered.indexOf("pg-primary-down")).isBetween(start, end);
  }

  @Test
  @DisplayName("a situation whose observations are gone renders without an empty fence")
  void shouldRenderWithNoObservations() {
    // Reachable: retention deletes the rows of a closed situation, and everything past
    // max-events-per-situation was only ever counted.
    final var rendered =
        notice().render(situation(), Situation.Phase.MONITORING, "boom", passthrough());

    assertThat(rendered).contains("Attempt 3");
    assertThat(rendered).doesNotContain("observed content").doesNotContain("Most recent");
  }

  @Test
  @DisplayName("nobody can notify a whole group by writing the right thing into an alert")
  void shouldEscapeEverythingItDidNotWrite() {
    // The security assertion. A title, an observation summary and a gateway's error message are all
    // written by somebody else, and a chat's markdown has tags that notify people — so every one of
    // them has to go through the surface's escaping on the way out. The error is the least obvious
    // and the one that was missing: a gateway refusing a request routinely echoes the request back,
    // and the request is the brief, which quotes the alert.
    when(notifier.quoted(anyString())).thenReturn("[escaped]");
    final var hostile =
        repos.situations.save(situation().toBuilder().title("<at id=all></at> look at me").build());
    event("e1", Duration.ofMinutes(1), "<at id=all></at> and this too");

    final var rendered =
        notice().render(hostile, Situation.Phase.MONITORING, "Refused: <at id=all></at>", notifier);

    assertThat(rendered).doesNotContain("<at id=all>");
    assertThat(rendered).contains("[escaped]");
    // Our own words are still ours, and are not run through a stranger's escaping.
    assertThat(rendered).contains("Attempt 3").contains("Triage failed");
  }

  @Test
  @DisplayName("a gateway that answered over several lines keeps them, inside the code block")
  void shouldKeepTheErrorsOwnLines() {
    // The one thing here that is not flattened. A refusal is usually a stack trace or a JSON body,
    // and it is the only value the headline puts in a code block — which is what makes its own
    // lines worth keeping rather than something that would break the message apart.
    final var rendered =
        notice()
            .render(
                situation(),
                Situation.Phase.MONITORING,
                "IllegalStateException: the gateway refused\n{\"error\": \"no such model\"}",
                passthrough());

    assertThat(rendered).contains("the gateway refused\n{\"error\": \"no such model\"}");
    // And still bounded by the fence it was put in, rather than running into the situation's own
    // lines below it.
    assertThat(rendered).contains("```\nIllegalStateException");
    assertThat(rendered).contains("Attempt 3");
  }

  @Test
  @DisplayName("a payload written over several lines stays on its own line in the notice")
  void shouldKeepAQuotedObservationToOneLine() {
    // A summary is one bullet in a list. Its own newlines would break out of the bullet and leave
    // half the notice loose in the chat, outside the fence that says whose words those are.
    event("e1", Duration.ofMinutes(1), "pg-primary-down\nseverity: critical\n\nrunbook: none");

    final var rendered =
        notice().render(situation(), Situation.Phase.MONITORING, "boom", passthrough());

    assertThat(rendered).contains("pg-primary-down severity: critical runbook: none");
    final var lines = rendered.split("\n");
    assertThat(lines[lines.length - 1]).contains("end observed content");
  }

  @Test
  @DisplayName("a Chinese workspace is told in Chinese, and the observations are left alone")
  void shouldRenderInTheWorkspaceLanguage() {
    event("e1", Duration.ofMinutes(3), "pg-primary-down firing");

    final var rendered =
        notice(TestI18n.messages(Locale.of("zh", "CN")))
            .render(situation(), Situation.Phase.MONITORING, "boom", passthrough());

    assertThat(rendered).contains("分析失败").contains("第 3 次分析").contains("3 分钟前");
    assertThat(rendered).contains("在再次观察到相关事件之前");
    assertThat(rendered).doesNotContain("Attempt").doesNotContain("ago");
    // Untouched, in the language the source said it in.
    assertThat(rendered).contains("pg-primary-down firing");
  }
}
