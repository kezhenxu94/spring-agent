package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * That every label placeholder in the card templates is one {@link FeishuMessages#renderCard} knows
 * how to fill.
 *
 * <p>The two are edited in different files and nothing connects them, so adding an element with a
 * placeholder and forgetting the substitution costs nothing at build time and nothing at startup:
 * the card renders with the literal text {@code {references}} sitting where its title should be.
 * This is the check that turns that into a failing test instead of something a person notices on a
 * card.
 */
class FeishuCardTemplatePlaceholdersTest {

  /** A label slot, as the templates write them: a bare word in braces. */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

  private static final List<String> TEMPLATES =
      List.of("feishu/card-elements.json", "feishu/reply-card.json");

  /**
   * The slots the run fills rather than the message source: the tool pane's title is the call the
   * run is on, and there is no label of it to translate. Named here so that the check above stays a
   * check on labels — the mistake it exists to catch is a title that reads {@code {references}} on
   * a card, and a slot filled from Java is not that mistake.
   */
  private static final List<String> RUN_FILLED = List.of("{title}");

  private static FeishuMessages messages(final Locale locale) {
    return new FeishuMessages(
        new FeishuProperties(null, null, null, null, null, null, null, locale, null, null, null));
  }

  private static String render(final String template, final Locale locale) throws Exception {
    return messages(locale)
        .renderCard(new ClassPathResource(template).getContentAsString(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("no placeholder survives rendering, in any template or any language")
  void everyPlaceholderIsFilled() throws Exception {
    for (final var template : TEMPLATES) {
      for (final var locale : List.of(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
        final var rendered = render(template, locale);
        final var leftOver =
            PLACEHOLDER
                .matcher(rendered)
                .results()
                .map(r -> r.group())
                .filter(placeholder -> !RUN_FILLED.contains(placeholder))
                .toList();
        assertThat(leftOver)
            .as("unfilled placeholders in %s for %s — add them to renderCard", template, locale)
            .isEmpty();
      }
    }
  }

  @Test
  @DisplayName("a rendered template is still valid JSON, whatever the label said")
  void renderingKeepsTheJsonValid() throws Exception {
    final var om = new JsonMapper();
    for (final var template : TEMPLATES) {
      for (final var locale : List.of(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
        final var rendered = render(template, locale);
        assertThat(om.readTree(rendered)).as("%s for %s", template, locale).isNotNull();
      }
    }
  }
}
