package me.kezhenxu94.springagent.integration.feishu;

import java.util.Map;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.PromptVariablesContributor;
import org.springframework.stereotype.Component;

/**
 * What the model has to know to write an answer that arrives looking right in Feishu.
 *
 * <p>An answer here is not plain markdown: it lands in the rich-text element of a card (see {@code
 * reply-card.json}), which is CommonMark plus a handful of Feishu tags — and two of those tags look
 * alike and are not. {@code <at>} notifies the person named; {@code <person>} draws them and
 * notifies nobody. Without being told, a model listing the twenty members of a group writes twenty
 * {@code <at>} tags and pings all of them, which is the mistake this exists to prevent.
 *
 * <p>Contributed as a {@link PromptVariablesContributor} rather than written into the application's
 * system prompt, so the rules travel with the integration that needs them: a deployment that swaps
 * the prompt for one of its own keeps them, and a surface that is not Feishu never sees them.
 *
 * @see <a
 *     href="https://open.feishu.cn/document/uAjLw4CM/ukzMukzMukzM/feishu-cards/card-json-v2-components/content-components/rich-text">富文本组件</a>
 */
@Component
public class FeishuReplyFormat implements PromptVariablesContributor {

  /**
   * Every rule that holds wherever the answer is going. Deliberately short on syntax the model
   * already knows from CommonMark, and long on the parts that are Feishu's own or that fail
   * silently: what notifies somebody, what a table does past five rows, what has to be escaped.
   */
  static final String COMMON =
      """
      # Writing for Feishu
      Your answer is rendered as Feishu card markdown: CommonMark, plus the tags below. \
      Other HTML is dropped, so do not reach for it.

      - Mention somebody, and they get a notification: `<at id=ou_xxx></at>`, where the id is an \
      open_id or a user_id. By email instead: `<at email=name@example.com></at>`. Several at \
      once: `<at ids=ou_1,ou_2></at>`. Mention a person when you need them to look, not to refer \
      to them.
      - Name somebody without notifying them: \
      `<person id='ou_xxx' show_name=true show_avatar=true style='normal'></person>`, which \
      renders their name and avatar and pings nobody. This is what a list of people wants — group \
      members, who owns what, who has not replied — since a list of `<at>` tags notifies every \
      one of them.
      - Emphasis and structure: `**bold**`, `*italic*`, `~~strikethrough~~`, `` `inline code` ``, \
      `> quote`, `#` to `######` headings, `-` or `1.` lists indented four spaces per level, and \
      `<hr>` alone on its line.
      - Colour a run of text with `<font color='red'>text</font>`, and label one with \
      `<text_tag color='green'>done</text_tag>`. Both take: neutral, blue, turquoise, lime, \
      orange, violet, indigo, wathet, green, yellow, red, purple, carmine.
      - Links need a scheme, http(s) only: `[text](https://example.com)`, or with a leading icon \
      `<link icon='chat_outlined' url='https://example.com'>text</link>`. A phone number the \
      mobile client can dial: `[+86 10 1234](tel://+861012345678)`.
      - Fence code with its language — json, java, sql, bash, yaml, python, shell, diff — so it is \
      highlighted rather than left as plain text.
      - Tables are ordinary pipe tables, but a card shows five rows at a time and paginates the \
      rest, and holds at most four tables. Anything longer belongs in a spreadsheet you link to.
      - Feishu emoji go in by key, `:DONE:` `:THUMBSUP:`; standard emoji as themselves.
      - An image written as `![alt](/absolute/path)` or `![alt](https://...)` is uploaded to the \
      tenant for you and shown inline — a path from GenerateImage or the artifacts directory works \
      as-is.
      - A timestamp as \
      `<local_datetime millisecond='1700000000000' format_type='date_num'></local_datetime>` shows \
      in each reader's own timezone. format_type: date_num, date, date_short, week, week_short, \
      time, time_sec, timezone.
      - A literal character markdown would eat has to be escaped as an HTML entity: `&#42;` for \
      *, `&#95;` for _, `&sim;` for ~, `&#60;` and `&#62;` for < and >, `&#35;` for #, `&#96;` \
      for a backtick.
      - One newline is a soft break the renderer may swallow; use a blank line where the break \
      matters.\
      """;

  /**
   * Only in a group: notifying everybody is a thing a group has to allow, and a card that tries it
   * where it is not allowed fails to send outright — the answer is lost, not merely unstyled. Left
   * out of a direct message, where there is nobody to notify but the one person reading.
   */
  static final String GROUP_ONLY =
      """

      - `<at id=all></at>` notifies the whole group, and only works where the group allows it: \
      where it does not, the card fails to send and your answer never arrives. Check \
      at_all_permission with FeishuGetChat before using it, and only when what you have to say \
      genuinely concerns everybody.\
      """;

  @Override
  public Map<String, Object> variables(final AgentRequest request) {
    final var group = "group".equalsIgnoreCase(request.chatType());
    return Map.of("replyFormat", group ? COMMON + GROUP_ONLY : COMMON);
  }
}
