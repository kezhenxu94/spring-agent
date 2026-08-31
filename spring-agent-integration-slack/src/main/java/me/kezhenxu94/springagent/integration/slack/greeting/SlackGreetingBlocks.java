package me.kezhenxu94.springagent.integration.slack.greeting;

import com.google.common.base.Strings;
import com.slack.api.model.block.LayoutBlock;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import me.kezhenxu94.springagent.integration.slack.handler.SlackBlockKit;
import org.springframework.stereotype.Component;

/**
 * The welcome and the update notes as Block Kit.
 *
 * <p>Built in code rather than poured into a JSON template, which is the one place this module
 * departs from its Feishu counterpart. A Feishu card is a document with named slots; Block Kit is a
 * list, and a list assembled in Java from the notes is both shorter and harder to get wrong than a
 * template with placeholders that have to survive being substituted into JSON. The words are still
 * a deployment's to rewrite — they live in {@code welcome.md} and {@code updates/N.md}.
 */
@Component
@RequiredArgsConstructor
public class SlackGreetingBlocks {

  private final SlackUpdates updates;
  private final SlackMessages messages;

  /** What somebody who has never been greeted is shown. */
  public List<LayoutBlock> welcome() {
    final var welcome = updates.welcome();
    final var blocks = new ArrayList<LayoutBlock>();
    if (!Strings.isNullOrEmpty(welcome.title())) {
      blocks.add(SlackBlockKit.header(welcome.title()));
    }
    blocks.addAll(SlackBlockKit.paragraphs(welcome.body()));
    if (welcome.suggestions().isEmpty()) {
      return blocks;
    }
    blocks.add(SlackBlockKit.context(messages.get("welcome-suggest-hint")));
    // One button per suggestion, and its value is the prompt itself — checked against what this
    // deployment ships before anything is fired, since a button's value arrives from whoever
    // pressed it rather than from the message as it was rendered. See SlackSuggestions.
    blocks.add(
        SlackBlockKit.actions(
            "sa_suggestions",
            welcome.suggestions().stream()
                .limit(5)
                .map(
                    prompt ->
                        (com.slack.api.model.block.element.BlockElement)
                            SlackBlockKit.button(
                                SlackSuggestions.ACTION_ID,
                                SlackBlockKit.clamp(prompt, 75),
                                prompt,
                                null))
                .toList()));
    return blocks;
  }

  /** What somebody coming back is shown: the notes above where they left off, and only those. */
  public List<LayoutBlock> update(final List<SlackUpdates.Note> notes) {
    final var blocks = new ArrayList<LayoutBlock>();
    blocks.add(SlackBlockKit.header(messages.get("update-title", notes.size())));
    for (final var note : notes) {
      blocks.add(
          SlackBlockKit.context(
              messages.get("update-version", note.version())
                  + (Strings.isNullOrEmpty(note.title()) ? "" : "  *" + note.title() + "*")));
      blocks.addAll(SlackBlockKit.paragraphs(note.body()));
    }
    return blocks;
  }
}
