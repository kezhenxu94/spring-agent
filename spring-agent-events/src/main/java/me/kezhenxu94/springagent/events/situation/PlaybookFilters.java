package me.kezhenxu94.springagent.events.situation;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.stereotype.Component;

/**
 * Which of an owner's documents count as a source's playbook, parsed once from what the deployment
 * wrote.
 *
 * <p>A class of its own for the reason {@link TriagePrompts} is one: {@code
 * EventsProperties.Policy} resolves the layers of configuration and stops there, and turning a
 * resolved value into the thing the module actually uses — a prompt read from a file, an expression
 * parsed from a string — is a separate job with its own failure modes.
 *
 * <p><b>Parsed at startup, and a bad one refuses to start.</b> An expression is only ever executed
 * on the timer, inside a triage run nobody is watching, and a malformed one that failed there would
 * look exactly like a knowledge base with nothing relevant in it: no error anybody sees, no
 * playbook, and a run that quietly triages on the prompt alone. The same reasoning as {@code
 * SituationSweeper} complaining about a missing {@code owner-user-id} at startup rather than at
 * three in the morning.
 *
 * <p><b>Why a filter is worth configuring at all, rather than reading the whole knowledge base.</b>
 * A triage run has the knowledge tools, and {@code IndexKnowledge} writes into the base owned by
 * the run's own identity — which is the base the playbook is read from. One bean carries both those
 * writes and {@code SearchKnowledge}, and a scenario can only withhold a bean whole, so withholding
 * the writes would cost the run the ability to look anything up at all. What stops a run from
 * authoring the playbook its successors read is therefore this filter: naming exact document ids
 * means a document the run indexed under an id of its own choosing is not a playbook, whatever it
 * says about itself. A blank filter gives that up, which is a deployment's decision to make
 * knowingly.
 */
@Slf4j
@Component
public class PlaybookFilters {

  private static final FilterExpressionTextParser PARSER = new FilterExpressionTextParser();

  private final EventsProperties properties;

  /**
   * Source to parsed expression, holding an entry only for a source that configured one. Populated
   * once and read from every sweep thereafter, so it is built before anything can read it and never
   * written again.
   */
  private final Map<String, Filter.Expression> parsed = new HashMap<>();

  public PlaybookFilters(final EventsProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void parseAll() {
    properties
        .sources()
        .keySet()
        .forEach(
            source ->
                properties
                    .policyFor(source)
                    .map(EventsProperties.Policy::playbook)
                    .filter(playbook -> playbook.filter() != null)
                    .ifPresent(playbook -> parsed.put(source, parse(source, playbook.filter()))));
    if (!parsed.isEmpty()) {
      log.info("Parsed playbook filters for {}", parsed.keySet());
    }
  }

  /** The expression narrowing this source's playbook, or null where it named none. */
  public Filter.Expression forSource(final String source) {
    return source == null ? null : parsed.get(source);
  }

  private static Filter.Expression parse(final String source, final String expression) {
    try {
      return PARSER.parse(expression);
    } catch (RuntimeException e) {
      // Named in full, because the useful part of this failure is which source and which text: the
      // parser's own message says where in the string it gave up but not what the string was for.
      throw new IllegalStateException(
          "app.events.sources."
              + source
              + ".playbook.filter is not a valid filter expression: "
              + expression,
          e);
    }
  }
}
