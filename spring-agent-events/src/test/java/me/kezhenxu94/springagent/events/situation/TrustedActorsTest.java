package me.kezhenxu94.springagent.events.situation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.observing.Actor;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who gets in, and — mostly — who does not.
 *
 * <p>This is an allow-list standing between anybody who can reach a source and text the agent will
 * read, so the tests worth having are the ones about the ways an allow-list stops being one: a
 * pattern that matches more than its author meant, an identity nobody authenticated slipping
 * through as "no rule applies", and a bad pattern discovered at three in the morning instead of on
 * deploy.
 */
class TrustedActorsTest {

  private static EventsProperties withActors(final List<String> actors) {
    return EventsProperties.builder()
        .enabled(true)
        .sources(
            Map.of(
                "github",
                EventsProperties.Source.builder()
                    .owner(EventsProperties.Owner.builder().userId("ou_agent").build())
                    .trustedActors(actors)
                    .build()))
        .build();
  }

  private static TrustedActors compiled(final EventsProperties properties) {
    final var actors = new TrustedActors(properties);
    actors.compileAll();
    return actors;
  }

  private static TrustedActors trusting(final String... actors) {
    return compiled(withActors(List.of(actors)));
  }

  @Test
  @DisplayName("an actor the deployment named is let through")
  void shouldTrustANamedActor() {
    assertThat(trusting("octocat").trusts("github", Actor.authenticated("octocat"))).isTrue();
  }

  @Test
  @DisplayName("anybody else is not")
  void shouldNotTrustAnyoneElse() {
    assertThat(trusting("octocat").trusts("github", Actor.authenticated("mallory"))).isFalse();
  }

  @Test
  @DisplayName("a pattern has to account for the whole name, so it cannot be worn as a disguise")
  void shouldMatchWholeNamesOnly() {
    final var actors = trusting("octocat");

    // The case this exists for. Under find() every one of these would be admitted, because the
    // trusted name appears somewhere inside them — and registering such a name costs an attacker
    // nothing.
    assertThat(actors.trusts("github", Actor.authenticated("evil-octocat"))).isFalse();
    assertThat(actors.trusts("github", Actor.authenticated("octocat-evil"))).isFalse();
    assertThat(actors.trusts("github", Actor.authenticated("notoctocatreally"))).isFalse();
  }

  @Test
  @DisplayName("several patterns are alternatives, and any one of them is enough")
  void shouldTrustAnyOfSeveral() {
    final var actors = trusting("octocat", "dependabot\\[bot\\]");

    assertThat(actors.trusts("github", Actor.authenticated("octocat"))).isTrue();
    assertThat(actors.trusts("github", Actor.authenticated("dependabot[bot]"))).isTrue();
    assertThat(actors.trusts("github", Actor.authenticated("mallory"))).isFalse();
  }

  @Test
  @DisplayName("case is ignored, since the identities being matched ignore it too")
  void shouldIgnoreCase() {
    assertThat(trusting("octocat").trusts("github", Actor.authenticated("OctoCat"))).isTrue();
    assertThat(
            trusting(".+@apache\\.org").trusts("github", Actor.authenticated("Someone@Apache.ORG")))
        .isTrue();
  }

  @Test
  @DisplayName("a deployment that means everybody says so, and is taken at its word")
  void shouldTrustEveryoneWhenAsked() {
    final var actors = trusting(".*");

    assertThat(actors.trusts("github", Actor.authenticated("octocat"))).isTrue();
    assertThat(actors.trusts("github", Actor.authenticated("anybody-at-all"))).isTrue();
  }

  @Test
  @DisplayName("a source nobody wrote a rule for trusts everybody, which is the shipped default")
  void shouldTrustEveryoneWhereNothingIsConfigured() {
    // Not an oversight to tighten later. An allow-list that arrived switched on would stop an
    // existing deployment's triage silently, which is the failure this module's startup checks
    // exist to avoid; SituationSweeper is what says so out loud instead.
    final var actors = compiled(withActors(null));

    assertThat(actors.trusts("github", Actor.authenticated("anybody"))).isTrue();
    assertThat(actors.trusts("github", null)).isTrue();
  }

  @Test
  @DisplayName("an empty list says nothing, exactly as an empty string does")
  void shouldTreatAnEmptyListAsUnset() {
    // The binder hands back an empty list for a key written with nothing under it. Meaning "trust
    // nobody" there would turn a typo into a source that silently receives nothing at all.
    assertThat(compiled(withActors(List.of())).trusts("github", Actor.authenticated("anybody")))
        .isTrue();
  }

  @Test
  @DisplayName("a source that authenticates nobody gets nobody in, once a rule exists")
  void shouldRefuseAnUnauthenticatedActor() {
    final var actors = trusting(".*");

    // Grafana's case: machine-generated, no actor to report. Even '.*' does not admit it, because
    // the question is not whether the name matches but whether there is a name anybody vouched for.
    assertThat(actors.trusts("github", null)).isFalse();
    // And email's case: a name the source could read but not check. It matches '.*' as readily as
    // any other string, which is exactly why what is matched is the authenticated name and not the
    // claimed one — an allow-list fed a claim admits whoever writes the claim.
    assertThat(actors.trusts("github", Actor.claimed("octocat"))).isFalse();
  }

  @Test
  @DisplayName("an implausibly long actor is refused without being matched against")
  void shouldRefuseAnOverlongActor() {
    // The patterns are the operator's and trusted; what they are run over is the payload's. A
    // pattern nobody thought was dangerous can take time exponential in the subject's length, so
    // the subject is bounded before it is ever offered to one.
    assertThat(trusting(".*").trusts("github", Actor.authenticated("a".repeat(321)))).isFalse();
    assertThat(trusting(".*").trusts("github", Actor.authenticated("a".repeat(320)))).isTrue();
  }

  @Test
  @DisplayName("a malformed pattern refuses to start, naming the source and the pattern")
  void shouldRefuseToStartOnABadPattern() {
    assertThatThrownBy(() -> trusting("octo(cat"))
        .isInstanceOf(IllegalStateException.class)
        // Both halves matter to whoever has to fix it: the regex engine's own message says where it
        // gave up, but not which source's setting the string came from.
        .hasMessageContaining("app.events.sources.github.trusted-actors")
        .hasMessageContaining("octo(cat");
  }

  @Test
  @DisplayName("a rule written for one source says nothing about another")
  void shouldNotApplyOneSourcesRuleToAnother() {
    // There is no global list, and this is why: 'octocat' is a GitHub login and means nothing as an
    // email address. A source with no rule of its own is unfiltered rather than filtered by
    // somebody else's vocabulary.
    assertThat(trusting("octocat").trusts("grafana", Actor.authenticated("whoever"))).isTrue();
  }
}
