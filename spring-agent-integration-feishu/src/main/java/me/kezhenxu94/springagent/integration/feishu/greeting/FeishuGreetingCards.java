package me.kezhenxu94.springagent.integration.feishu.greeting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.greeting.FeishuUpdates.Note;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The two cards a person is greeted with: what this is, the first time, and what has changed, every
 * time after that.
 *
 * <p>They are one design at two moments — a ledger. Rows down the left, nothing centred, the note's
 * own prose carrying the card and the frame around it kept to a title and a marker. What tells them
 * apart is the one rule they both hold to: <b>a border means you can press it</b>. The welcome
 * card's suggestions are bordered because tapping one asks the question; an update note is not,
 * because tapping it does nothing, and a box around a row that does nothing is a promise the card
 * cannot keep.
 *
 * <p>Sent as finished {@code interactive} messages, the way {@link
 * me.kezhenxu94.springagent.integration.feishu.FeishuMessageCard} is. Nothing streams into either
 * of them, so neither is a card-kit card and neither carries a stop button.
 */
@Component
@RequiredArgsConstructor
public class FeishuGreetingCards {

  private final JsonMapper objectMapper;
  private final FeishuMessages messages;
  private final FeishuUpdates updates;

  // Constructor arguments rather than @Value fields, for the reason FeishuMessageCard sets out: AOT
  // writes a plain assignment for an injected field and cannot target a final one, and these stay
  // final because lombok.config lists @Value as copyable.
  @Value("${app.feishu.welcome-card:classpath:/feishu/welcome-card.json}")
  private final Resource welcomeCardTemplate;

  @Value("${app.feishu.update-card:classpath:/feishu/update-card.json}")
  private final Resource updateCardTemplate;

  /** The card a person sees the first time they open the chat. */
  public String welcome() throws IOException {
    final var note = updates.welcome();
    final var card =
        (ObjectNode)
            objectMapper.readTree(
                messages.renderWelcomeCard(
                    welcomeCardTemplate.getContentAsString(StandardCharsets.UTF_8)));

    final var title = card.path("header").path("title");
    if (title instanceof ObjectNode titleNode && !note.title().isEmpty()) {
      titleNode.put("content", note.title());
    }

    final var elements = (ArrayNode) card.path("body").path("elements");
    fill(elements, "intro", note.body());
    if (note.suggestions().isEmpty()) {
      // A caption over nothing reads as a card that failed to load half of itself.
      remove(elements, "suggest_hint");
    } else {
      note.suggestions().forEach(prompt -> elements.add(chip(prompt)));
    }
    return objectMapper.writeValueAsString(card);
  }

  /** The card listing {@code notes}, which are the ones this person has not been shown. */
  public String update(final List<Note> notes) throws IOException {
    final var card =
        (ObjectNode)
            objectMapper.readTree(
                messages.renderUpdateCard(
                    updateCardTemplate.getContentAsString(StandardCharsets.UTF_8), notes.size()));
    final var elements = (ArrayNode) card.path("body").path("elements");
    notes.forEach(note -> elements.add(entry(note)));
    return objectMapper.writeValueAsString(card);
  }

  /**
   * One thing the card offers to ask, as the bordered row {@code docs/交互容器.md} calls an interactive
   * container.
   *
   * <p>The prompt rides in the callback's value <em>and</em> is what the row reads, so the person
   * presses the words they are about to send. Nothing else is carried: what a tap does is decided
   * from the prompt alone, and against the list this deployment actually ships — see {@link
   * FeishuUpdates#offers}.
   */
  private ObjectNode chip(final String prompt) {
    final var chip = objectMapper.createObjectNode();
    chip.put("tag", "interactive_container");
    chip.put("width", "fill");
    chip.put("height", "auto");
    chip.put("horizontal_align", "left");
    chip.put("background_style", "default");
    chip.put("has_border", true);
    chip.put("border_color", "grey");
    chip.put("corner_radius", "8px");
    chip.put("padding", "6px 12px 6px 12px");

    final var behavior = chip.putArray("behaviors").addObject();
    behavior.put("type", "callback");
    final var value = behavior.putObject("value");
    value.put("button", FeishuSuggestions.ACTION);
    value.put("prompt", prompt);

    final var text = chip.putArray("elements").addObject();
    text.put("tag", "markdown");
    text.put("content", prompt);
    final var icon = text.putObject("icon");
    icon.put("tag", "standard_icon");
    icon.put("token", "chat_outlined");
    icon.put("color", "grey");
    return chip;
  }

  /**
   * One note, as a ledger line: its number in a gutter down the left, its title and its prose
   * beside. The gutter is what makes a list of notes read as a numbered record rather than as an
   * announcement, and it is the only place the version the agent tracks is ever shown to the person
   * it is tracked for.
   */
  private ObjectNode entry(final Note note) {
    final var row = objectMapper.createObjectNode();
    row.put("tag", "column_set");
    row.put("flex_mode", "none");
    row.put("background_style", "default");
    row.put("horizontal_spacing", "12px");
    final var columns = row.putArray("columns");

    final var gutter = columns.addObject();
    gutter.put("tag", "column");
    gutter.put("width", "auto");
    gutter.put("vertical_align", "top");
    final var marker = gutter.putArray("elements").addObject();
    marker.put("tag", "markdown");
    marker.put("content", grey(messages.get("update-version", note.version())));
    marker.put("text_size", "notation");

    final var column = columns.addObject();
    column.put("tag", "column");
    column.put("width", "weighted");
    column.put("weight", 1);
    column.put("vertical_align", "top");
    column.put("vertical_spacing", "4px");
    final var body = column.putArray("elements");
    final var title = body.addObject();
    title.put("tag", "markdown");
    title.put("content", "**" + note.title() + "**");
    if (!note.body().isEmpty()) {
      final var prose = body.addObject();
      prose.put("tag", "markdown");
      prose.put("content", note.body());
    }
    return row;
  }

  private static String grey(final String text) {
    return "<font color='grey'>" + text + "</font>";
  }

  /** Writes {@code content} into the template's element of that id, where the template kept one. */
  private static void fill(final ArrayNode elements, final String elementId, final String content) {
    for (final var element : elements) {
      if (element instanceof ObjectNode node
          && elementId.equals(node.path("element_id").asString())) {
        node.put("content", content);
        return;
      }
    }
  }

  private static void remove(final ArrayNode elements, final String elementId) {
    final var iterator = elements.iterator();
    while (iterator.hasNext()) {
      if (elementId.equals(iterator.next().path("element_id").asString())) {
        iterator.remove();
        return;
      }
    }
  }
}
