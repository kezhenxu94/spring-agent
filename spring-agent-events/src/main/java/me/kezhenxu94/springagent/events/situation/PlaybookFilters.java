package me.kezhenxu94.springagent.events.situation;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeMetadata;
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

  /**
   * The document ids this source's filter accepts, or empty where it accepts something other than a
   * named set of them — a filter over another field, one that excludes rather than includes, or no
   * filter at all.
   *
   * <p>What this is for: an administrator writing a playbook needs to know which ids will actually
   * be read as one, because a document stored under any other id is stored perfectly well and then
   * never retrieved. Nothing complains, the triage run simply goes on triaging without a playbook.
   *
   * <p><b>Not a security check.</b> What keeps a run from authoring its own playbook is that {@code
   * PlaybookTools} is an {@code AdminTool} and every unattended scenario withholds those — see the
   * class comment above and {@code SituationTriageScenario}. This is the guard against a silent
   * mistake by somebody who is allowed to be here.
   *
   * <p>Empty is deliberately ambiguous between "no filter" and "a filter this cannot read", and the
   * caller keeps them apart by asking {@link #forSource} as well. An unreadable filter must not be
   * reported as an accepted id, and a write under it cannot be promised to be read.
   */
  public Set<String> docIdsFor(final String source) {
    final var expression = forSource(source);
    if (expression == null) {
      return Set.of();
    }
    final var ids = new LinkedHashSet<String>();
    return collectDocIds(expression, ids) ? ids : Set.of();
  }

  /**
   * Adds to {@code ids} the document ids {@code operand} accepts, answering whether the whole of it
   * was understood.
   *
   * <p>False the moment anything is met that could widen or invert the set — a comparison on
   * another field, a negation, an operator other than equality or membership. Answering false is
   * what makes an unreadable filter report no ids rather than some of them: half an answer here
   * would tell an administrator that an id is accepted when the surrounding expression rejects it.
   */
  private static boolean collectDocIds(final Filter.Operand operand, final Set<String> ids) {
    if (operand instanceof Filter.Group group) {
      return collectDocIds(group.content(), ids);
    }
    if (!(operand instanceof Filter.Expression expression)) {
      return false;
    }
    return switch (expression.type()) {
      case AND, OR ->
          collectDocIds(expression.left(), ids) && collectDocIds(expression.right(), ids);
      case EQ, IN -> {
        if (!(expression.left() instanceof Filter.Key key)
            || !KnowledgeMetadata.DOC_ID.equals(key.key())
            || !(expression.right() instanceof Filter.Value value)) {
          yield false;
        }
        if (value.value() instanceof List<?> values) {
          values.forEach(v -> ids.add(String.valueOf(v)));
        } else {
          ids.add(String.valueOf(value.value()));
        }
        yield true;
      }
      default -> false;
    };
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
