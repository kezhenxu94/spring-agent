package me.kezhenxu94.springagent.events.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.observing.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three layers a source's settings come from, and the order they win in: what the deployment
 * said, then what the shipped source needs, then what applies to everything.
 *
 * <p>Worth pinning because the layering is invisible at the call site — the rest of the module
 * reads a resolved {@code Policy} and cannot tell where any of it came from — and because the top
 * of that order is a security property: a source nobody configured must resolve to nothing at all
 * rather than to the general policy, or a webhook path with no secret would accept whatever
 * arrived.
 */
class EventsPropertiesTest {

  @Test
  @DisplayName("a source nobody configured has no policy, so its observations are dropped")
  void shouldHaveNoPolicyForAnUnconfiguredSource() {
    assertThat(properties(Map.of()).policyFor("github")).isEmpty();
  }

  @Test
  @DisplayName("nothing has a policy while the feature is off, however well configured")
  void shouldHaveNoPolicyWhileDisabled() {
    final var off =
        EventsProperties.builder()
            .sources(
                Map.of(
                    "github",
                    EventsProperties.Source.builder()
                        .owner(EventsProperties.Owner.builder().userId("ou_bot").build())
                        .build()))
            .build();

    assertThat(off.policyFor("github")).isEmpty();
  }

  @Test
  @DisplayName("a configured source can be turned off without losing its settings")
  void shouldHaveNoPolicyForADisabledSource() {
    final var properties =
        properties(
            Map.of("github", EventsProperties.Source.builder().enabled(false).secret("s").build()));

    assertThat(properties.policyFor("github")).isEmpty();
  }

  @Test
  @DisplayName("a source that overrides nothing takes the top-level settings")
  void shouldFallBackToTheTopLevel() {
    final var properties =
        properties(Map.of("github", EventsProperties.Source.builder().secret("s").build()));

    final var policy = properties.policyFor("github").orElseThrow();

    assertThat(policy.source()).isEqualTo("github");
    assertThat(policy.secret()).isEqualTo("s");
    assertThat(policy.debounce()).isEqualTo(EventsProperties.DEFAULT_DEBOUNCE);
    assertThat(policy.maxDebounce()).isEqualTo(EventsProperties.DEFAULT_MAX_DEBOUNCE);
    assertThat(policy.cooldown()).isEqualTo(EventsProperties.DEFAULT_COOLDOWN);
    assertThat(policy.resolveAfterQuiet()).isEqualTo(EventsProperties.DEFAULT_RESOLVE_AFTER_QUIET);
    assertThat(policy.resolveAfterEvaluation())
        .isEqualTo(EventsProperties.DEFAULT_RESOLVE_AFTER_EVALUATION);
    // Nothing said about the prompt, so nothing is resolved here: the sweeper asks TriagePrompts
    // for the source's own file, which is the only answer that can be in the workspace's language.
    assertThat(policy.triagePrompt()).isNull();
    // Nothing said, so nowhere to talk: a webhook source is told where by configuration or not at
    // all, and NONE is what an unset route resolves to rather than a null to be checked for.
    assertThat(policy.route()).isEqualTo(Route.NONE);
  }

  @Test
  @DisplayName("what the deployment says beats everything else")
  void shouldLetTheDeploymentWin() {
    final var properties =
        properties(
            Map.of(
                "github",
                EventsProperties.Source.builder()
                    .secret("s")
                    .owner(EventsProperties.Owner.builder().userId("ou_bot").build())
                    .debounce(Duration.ofSeconds(1))
                    .cooldown(Duration.ofSeconds(2))
                    .triagePrompt("look at {situation}")
                    .route(Route.builder().chatId("oc_alerts").chatType("group").build())
                    .build()));

    final var policy = properties.policyFor("github").orElseThrow();

    assertThat(policy.debounce()).isEqualTo(Duration.ofSeconds(1));
    assertThat(policy.cooldown()).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.triagePrompt()).isEqualTo("look at {situation}");
    assertThat(policy.owner().userId()).isEqualTo("ou_bot");
    assertThat(policy.route().chatId()).isEqualTo("oc_alerts");
    // Untouched settings still come from the top level rather than being lost with the ones set.
    assertThat(policy.maxDebounce()).isEqualTo(EventsProperties.DEFAULT_MAX_DEBOUNCE);
  }

  @Test
  @DisplayName("a chat is watched on its own terms, not on an alert's")
  void shouldApplyTheBuiltInChatPolicy() {
    // The reason the layering exists at all. A chat wants long enough for the people in it to
    // answer
    // each other, a much longer cooldown so the agent does not become a presence in the
    // conversation, a window that closes after one look, and a completely different prompt: nothing
    // is wrong, the question is whether there is an unanswered question.
    final var properties =
        properties(
            Map.of(
                EventsProperties.FEISHU_CHAT,
                EventsProperties.Source.builder()
                    .owner(EventsProperties.Owner.builder().userId("ou_bot").build())
                    .build()));

    final var policy = properties.policyFor(EventsProperties.FEISHU_CHAT).orElseThrow();

    assertThat(policy.debounce()).isEqualTo(Duration.ofSeconds(45));
    assertThat(policy.cooldown()).isEqualTo(Duration.ofMinutes(30));
    assertThat(policy.resolveAfterEvaluation()).isTrue();
    // Timings only. What this source says to the model is a file rather than a value, so that it
    // can
    // be translated; TriagePromptsTest is where that half is pinned.
    assertThat(policy.triagePrompt()).isNull();
  }

  @Test
  @DisplayName("and a deployment can still overrule what a shipped source asks for")
  void shouldLetTheDeploymentOverruleTheBuiltIn() {
    final var properties =
        properties(
            Map.of(
                EventsProperties.FEISHU_CHAT,
                EventsProperties.Source.builder()
                    .owner(EventsProperties.Owner.builder().userId("ou_bot").build())
                    .cooldown(Duration.ofMinutes(5))
                    .build()));

    final var policy = properties.policyFor(EventsProperties.FEISHU_CHAT).orElseThrow();

    assertThat(policy.cooldown()).isEqualTo(Duration.ofMinutes(5));
    // The rest of the built-in policy survives being partly overridden.
    assertThat(policy.debounce()).isEqualTo(Duration.ofSeconds(45));
    assertThat(policy.resolveAfterEvaluation()).isTrue();
  }

  @Test
  @DisplayName("a blank prompt is no prompt, not an empty one")
  void shouldTreatABlankPromptAsUnset() {
    // Easy to arrive at from a yaml naming an environment variable nobody set.
    final var properties =
        properties(
            Map.of(
                "github", EventsProperties.Source.builder().secret("s").triagePrompt(" ").build()));

    assertThat(properties.policyFor("github").orElseThrow().triagePrompt()).isNull();
  }

  @Test
  @DisplayName("the compact constructor fills in what the binder left, for a directly built one")
  void shouldDefaultEverythingWhenBuiltDirectly() {
    final var properties = EventsProperties.builder().enabled(true).build();

    assertThat(properties.sweepInterval()).isEqualTo(EventsProperties.DEFAULT_SWEEP_INTERVAL);
    assertThat(properties.maxEvidence()).isEqualTo(EventsProperties.DEFAULT_MAX_EVIDENCE);
    assertThat(properties.triagePrompt()).isNull();
    assertThat(properties.sources()).isEmpty();
  }

  @Test
  @DisplayName("a source with no playbook of its own takes the top-level one")
  void shouldInheritThePlaybook() {
    final var properties =
        EventsProperties.builder()
            .enabled(true)
            .playbook(EventsProperties.Playbook.builder().query("how to deal with events").build())
            .sources(Map.of("github", EventsProperties.Source.builder().secret("s").build()))
            .build();

    final var playbook = properties.policyFor("github").orElseThrow().playbook();
    assertThat(playbook.query()).isEqualTo("how to deal with events");
    assertThat(playbook.hasQuery()).isTrue();
  }

  @Test
  @DisplayName("and a source that states one replaces it whole, filter and query together")
  void shouldOverrideThePlaybookWhole() {
    // Whole rather than field by field, so a top-level filter pinned to one source's documents can
    // never be applied to another source's query — which retrieves nothing and looks, from the
    // outside, exactly like an empty knowledge base.
    final var properties =
        EventsProperties.builder()
            .enabled(true)
            .playbook(
                EventsProperties.Playbook.builder()
                    .query("the general one")
                    .filter("docId == 'general'")
                    .build())
            .sources(
                Map.of(
                    "github",
                    EventsProperties.Source.builder()
                        .playbook(
                            EventsProperties.Playbook.builder()
                                .query("how to handle GitHub")
                                .build())
                        .build()))
            .build();

    final var playbook = properties.policyFor("github").orElseThrow().playbook();
    assertThat(playbook.query()).isEqualTo("how to handle GitHub");
    assertThat(playbook.filter()).isNull();
  }

  @Test
  @DisplayName("no playbook anywhere is no playbook, not an empty lookup")
  void shouldHaveNoPlaybookByDefault() {
    final var properties =
        properties(Map.of("github", EventsProperties.Source.builder().secret("s").build()));

    final var playbook = properties.policyFor("github").orElseThrow().playbook();
    assertThat(playbook.hasQuery()).isFalse();
    assertThat(playbook.filter()).isNull();
  }

  @Test
  @DisplayName("a blank query or filter is the same as saying nothing")
  void shouldTreatBlankPlaybookFieldsAsAbsent() {
    final var playbook = EventsProperties.Playbook.builder().query("  ").filter("").build();

    assertThat(playbook.query()).isNull();
    assertThat(playbook.filter()).isNull();
    assertThat(playbook.hasQuery()).isFalse();
  }

  @Test
  @DisplayName("a source states whose events it wants, and gets exactly that list back")
  void shouldResolveTrustedActors() {
    final var properties =
        properties(
            Map.of(
                "github",
                EventsProperties.Source.builder()
                    .trustedActors(List.of("octocat", "dependabot\\[bot\\]"))
                    .build()));

    assertThat(properties.policyFor("github").orElseThrow().trustedActors())
        .containsExactly("octocat", "dependabot\\[bot\\]");
  }

  @Test
  @DisplayName("a source that names nobody is unfiltered, which is what an upgrade wants")
  void shouldHaveNoTrustedActorsByDefault() {
    // Null and not an empty list, because the two would have to mean opposite things and only one
    // of them can be the default. Absent means everybody, so a deployment that upgrades into this
    // feature carries on triaging; SituationSweeper is what says out loud that it is doing so.
    final var properties =
        properties(Map.of("github", EventsProperties.Source.builder().secret("s").build()));

    assertThat(properties.policyFor("github").orElseThrow().trustedActors()).isNull();
  }

  @Test
  @DisplayName("an empty list says nothing, exactly as a blank string does")
  void shouldTreatAnEmptyTrustedActorsListAsAbsent() {
    // The binder hands back an empty list for a key written with nothing under it, and pick() takes
    // the first non-null — so without emptyToNull this would beat the layers beneath it and mean
    // something quite different from leaving the setting out.
    final var properties =
        properties(
            Map.of("github", EventsProperties.Source.builder().trustedActors(List.of()).build()));

    assertThat(properties.policyFor("github").orElseThrow().trustedActors()).isNull();
  }

  @Test
  @DisplayName("there is no top-level list for a source to inherit from")
  void shouldNotShareTrustedActorsBetweenSources() {
    // Deliberately unlike every timing around it. Patterns are written in one source's vocabulary —
    // GitHub logins, email addresses — and a global list applied to a source it was never written
    // for either admits the wrong people or, far more likely, silently admits nobody at all.
    final var properties =
        properties(
            Map.of(
                "github",
                EventsProperties.Source.builder().trustedActors(List.of("octocat")).build(),
                "grafana",
                EventsProperties.Source.builder().secret("s").build()));

    assertThat(properties.policyFor("grafana").orElseThrow().trustedActors()).isNull();
  }

  static EventsProperties properties(final Map<String, EventsProperties.Source> sources) {
    return EventsProperties.builder().enabled(true).sources(sources).build();
  }
}
