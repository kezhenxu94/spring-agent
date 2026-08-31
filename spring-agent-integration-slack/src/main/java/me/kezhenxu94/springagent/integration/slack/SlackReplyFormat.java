package me.kezhenxu94.springagent.integration.slack;

import java.util.Map;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.PromptVariablesContributor;
import org.springframework.stereotype.Component;

/**
 * What the model has to know to write an answer that arrives looking right in Slack.
 *
 * <p><b>Slack mrkdwn is not CommonMark, and the two disagree about the most common thing a model
 * writes.</b> Bold is one asterisk here, not two, so an answer written in ordinary markdown arrives
 * with its emphasis showing as literal asterisks — on every bold word, in every answer. There are
 * no headings and no tables at all. This is the single largest reason an answer looks wrong in
 * Slack, and it is invisible until somebody reads one.
 *
 * <p>The other half is who gets notified. {@code <@U123>} pings the person named; their plain name
 * does not. Without being told, a model listing the twenty members of a channel writes twenty
 * mentions and notifies all of them — the same mistake {@code FeishuReplyFormat} exists to prevent,
 * in the same shape.
 *
 * <p>Contributed as a {@link PromptVariablesContributor} rather than written into the application's
 * system prompt, so the rules travel with the integration that needs them: a deployment that swaps
 * the prompt for one of its own keeps them, and a surface that is not Slack never sees them.
 *
 * @see <a href="https://docs.slack.dev/messaging/formatting-message-text">Formatting message
 *     text</a>
 */
@Component
public class SlackReplyFormat implements PromptVariablesContributor {

  /**
   * Every rule that holds wherever the answer is going. Long on the places Slack differs from the
   * CommonMark the model already knows, and on what fails silently; short on everything else.
   */
  static final String COMMON =
      """
      # Writing for Slack
      Your answer is rendered as Slack mrkdwn, which is NOT CommonMark. The differences below \
      are the ones that matter; anything not listed does not exist in Slack and is shown as the \
      literal characters you typed.

      - Emphasis is single-character: `*bold*` (NOT `**bold**`), `_italic_`, `~strikethrough~`, \
      `` `inline code` ``. Writing `**bold**` puts visible asterisks in your answer.
      - There are NO headings. `#` is shown as a literal hash. To open a section, use a short \
      `*bold line*` on its own instead.
      - There are NO tables. A pipe table arrives as a wall of pipes. Use a short list, or one \
      `field: value` per line.
      - Lists are `-` or `1.` at the start of a line, indented by two spaces per level. A blank \
      line between items is what keeps them apart.
      - `> quote` for a block quote, and every line of a multi-line quote needs its own `>`.
      - Fence code with triple backticks. Slack ignores the language after the fence, so do not \
      rely on highlighting, but the fence itself is what keeps indentation and newlines.
      - Links carry their text inside the angle brackets: `<https://example.com|the text>`, or \
      `<https://example.com>` bare. CommonMark's `[text](url)` does NOT work and arrives as its \
      own punctuation.
      - Mention somebody, and they get a notification: `<@U123ABC>`, where the id is a Slack user \
      id. Mention a person when you need them to look, not to refer to them — to name somebody \
      without notifying them, write their display name as plain text. A list of people wants plain \
      names, since a list of mentions notifies every one of them.
      - Link a channel with `<#C123ABC>`, which notifies nobody.
      - A literal character mrkdwn would eat has to be escaped as an HTML entity: `&amp;` for &, \
      `&lt;` for <, `&gt;` for >. Those three only; everything else goes in as itself.
      - An image written as `![alt](/absolute/path)` or `![alt](https://...)` is uploaded to the \
      workspace for you and shown inline — a path from GenerateImage or the artifacts directory \
      works as-is.
      - A timestamp as `<!date^1700000000^{date_short} {time}|17 Nov 2023>` shows in each reader's \
      own timezone; the text after the pipe is what a client that cannot render it falls back to.
      - Keep any one paragraph under about 3000 characters. A longer one is split across blocks, \
      which is safe but puts the break wherever it falls rather than where you wanted it.\
      """;

  /**
   * Only in a channel. Notifying everybody present is something a person feels, and a channel is
   * where there is somebody other than the reader to feel it — in a direct message there is nobody
   * to notify but the one person already reading.
   */
  static final String CHANNEL_ONLY =
      """

      - `<!here>` notifies everybody currently online in the channel and `<!channel>` notifies \
      every member whether they are here or not. Both interrupt people who did not ask you \
      anything, and `<!channel>` in a busy channel reaches hundreds of them. Use neither unless \
      what you have to say genuinely concerns everybody, and prefer naming the two or three people \
      it actually concerns.\
      """;

  @Override
  public Map<String, Object> variables(final AgentRequest request) {
    final var channel = !"p2p".equalsIgnoreCase(request.chatType());
    return Map.of("replyFormat", channel ? COMMON + CHANNEL_ONLY : COMMON);
  }
}
