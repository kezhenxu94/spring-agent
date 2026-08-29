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
  @DisplayName("and neither has a source nobody has heard of")
  void shouldHaveNoFilterForAnUnknownSource() {
    final var filters = parsed(withFilter("docId == 'runbook-github'"));

    assertThat(filters.forSource("grafana")).isNull();
    assertThat(filters.forSource(null)).isNull();
  }
}
