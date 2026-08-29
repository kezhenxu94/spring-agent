package me.kezhenxu94.springagent.events.situation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.converter.PrintFilterExpressionConverter;

/**
 * When a malformed playbook filter is discovered, which is the whole point of this class.
 *
 * <p>An expression is only ever executed inside a triage run on a timer, with nobody watching, so a
 * bad one that failed there would be indistinguishable from a knowledge base with nothing relevant
 * in it. Refusing to start is the difference between finding out on deploy and never finding out.
 */
class PlaybookFiltersTest {

  private static EventsProperties withFilter(final String filter) {
    return EventsProperties.builder()
        .enabled(true)
        .sources(
            Map.of(
                "github",
                EventsProperties.Source.builder()
                    .ownerUserId("ou_agent")
                    .playbook(
                        EventsProperties.Playbook.builder().query("how to").filter(filter).build())
                    .build()))
        .build();
  }

  private static PlaybookFilters parsed(final EventsProperties properties) {
    final var filters = new PlaybookFilters(properties);
    filters.parseAll();
    return filters;
  }

  @Test
  @DisplayName("a well-formed expression is parsed once, at startup")
  void shouldParseAGoodExpression() {
    final var filters = parsed(withFilter("docId in ['runbook-github','runbook-oncall']"));

    assertThat(new PrintFilterExpressionConverter().convertExpression(filters.forSource("github")))
        .isEqualTo("docId IN [\"runbook-github\",\"runbook-oncall\"]");
  }

  @Test
  @DisplayName("a malformed one refuses to start, naming the source and the text")
  void shouldRefuseToStartOnABadExpression() {
    assertThatThrownBy(() -> parsed(withFilter("docId in [")))
        .isInstanceOf(IllegalStateException.class)
        // Both halves matter to whoever has to fix it: the parser's own message says where in the
        // string it gave up, but not which source the string belongs to.
        .hasMessageContaining("app.events.sources.github.playbook.filter")
        .hasMessageContaining("docId in [");
  }

  @Test
  @DisplayName("a source that configured no filter has none, rather than one matching nothing")
  void shouldHaveNoFilterWhereNoneWasConfigured() {
    assertThat(parsed(withFilter(null)).forSource("github")).isNull();
  }

  @Test
  @DisplayName("the document ids a filter names are readable, so a playbook can be written to one")
  void shouldReadTheDocumentIdsOutOfAFilter() {
    assertThat(
            parsed(withFilter("docId in ['runbook-github','runbook-oncall']")).docIdsFor("github"))
        .containsExactly("runbook-github", "runbook-oncall");
    assertThat(parsed(withFilter("docId == 'runbook-github'")).docIdsFor("github"))
        .containsExactly("runbook-github");
    // Both branches of an or, since either would be retrieved.
    assertThat(parsed(withFilter("docId == 'a' || docId in ['b','c']")).docIdsFor("github"))
        .containsExactly("a", "b", "c");
  }

  @Test
  @DisplayName("a filter it cannot read the ids out of names none, rather than some of them")
  void shouldNameNoIdsWhereItCannotReadThemAll() {
    // The dangerous answer would be a partial one: "docId == 'a'" is in here, but the surrounding
    // expression rejects it, and reporting it as accepted would send an administrator to write a
    // playbook that is then filtered out.
    assertThat(parsed(withFilter("docId == 'a' && source == 'wiki'")).docIdsFor("github"))
        .isEmpty();
    assertThat(parsed(withFilter("docId != 'a'")).docIdsFor("github")).isEmpty();
    assertThat(parsed(withFilter("docId nin ['a']")).docIdsFor("github")).isEmpty();
    assertThat(parsed(withFilter("source == 'wiki'")).docIdsFor("github")).isEmpty();
  }

  @Test
  @DisplayName("no filter names no ids either, which the caller tells apart by asking forSource")
  void shouldNameNoIdsWhereThereIsNoFilter() {
    final var filters = parsed(withFilter(null));

    assertThat(filters.docIdsFor("github")).isEmpty();
    // The two empties mean different things — anything is accepted here, nothing is knowable above
    // — and this is what keeps them apart.
    assertThat(filters.forSource("github")).isNull();
  }

  @Test
  @DisplayName("and neither has a source nobody has heard of")
  void shouldHaveNoFilterForAnUnknownSource() {
    final var filters = parsed(withFilter("docId == 'runbook-github'"));

    assertThat(filters.forSource("grafana")).isNull();
    assertThat(filters.forSource(null)).isNull();
    assertThat(filters.docIdsFor("grafana")).isEmpty();
  }
}
