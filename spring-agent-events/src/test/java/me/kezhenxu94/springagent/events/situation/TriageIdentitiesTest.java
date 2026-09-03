package me.kezhenxu94.springagent.events.situation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.identity.SystemIdentity;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which identities a surface is told about, and which it is not.
 *
 * <p>These ids are offered as somebody to read a knowledge base as, so what matters is that the
 * list is the identities triage actually runs under: an id from a source nobody turned on, or from
 * a whole feature that is off, is an id whose knowledge base nothing is writing into.
 */
class TriageIdentitiesTest {

  @Test
  @DisplayName("every configured source's owner, each said once, with what it is used for")
  void shouldListTheOwnersOfConfiguredSources() {
    final var sources = new LinkedHashMap<String, EventsProperties.Source>();
    sources.put("grafana", source("ou_alerts"));
    // Two sources, one identity: they share a knowledge base, so they are one thing to read.
    sources.put("gitlab", source("ou_code"));
    sources.put("github", source("ou_code"));

    assertThat(identities(sources))
        .extracting(SystemIdentity::userId, SystemIdentity::sources)
        .containsExactly(
            tuple("ou_alerts", List.of("grafana")), tuple("ou_code", List.of("github", "gitlab")));
  }

  @Test
  @DisplayName("a source with no owner is left out rather than offered as a blank id")
  void shouldSkipASourceWithNoOwner() {
    assertThat(identities(Map.of("github", EventsProperties.Source.builder().secret("s").build())))
        .isEmpty();
  }

  @Test
  @DisplayName("a source that is turned off runs as nobody, so it names nobody")
  void shouldSkipADisabledSource() {
    final var off =
        EventsProperties.Source.builder()
            .enabled(false)
            .owner(EventsProperties.Owner.builder().userId("ou_alerts").build())
            .build();

    assertThat(identities(Map.of("grafana", off))).isEmpty();
  }

  @Test
  @DisplayName("nothing is offered while the whole feature is off")
  void shouldOfferNothingWhileDisabled() {
    final var properties =
        EventsProperties.builder().sources(Map.of("github", source("ou_code"))).build();

    assertThat(new TriageIdentities(properties).identities()).isEmpty();
  }

  private static List<SystemIdentity> identities(
      final Map<String, EventsProperties.Source> sources) {
    return new TriageIdentities(EventsProperties.builder().enabled(true).sources(sources).build())
        .identities();
  }

  private static EventsProperties.Source source(final String userId) {
    return EventsProperties.Source.builder()
        .owner(EventsProperties.Owner.builder().userId(userId).build())
        .build();
  }
}
