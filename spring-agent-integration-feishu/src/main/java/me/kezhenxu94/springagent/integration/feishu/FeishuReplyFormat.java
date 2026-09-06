package me.kezhenxu94.springagent.integration.feishu;

import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.PromptVariablesContributor;
import me.kezhenxu94.springagent.core.config.LocalizedPrompt;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuGuides;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.springframework.beans.factory.annotation.Autowired;
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
 * the prompt for one of its own keeps them, and a surface that is not Feishu never sees them —
 * which is what the chat-type gate in {@link #variables(AgentRequest)} is for, since a contributor
 * bean is asked about every run in the context and not only about this surface's.
 *
 * @see <a
 *     href="https://open.feishu.cn/document/uAjLw4CM/ukzMukzMukzM/feishu-cards/card-json-v2-components/content-components/rich-text">富文本组件</a>
 */
@Component
public class FeishuReplyFormat implements PromptVariablesContributor {

  /** Every rule that holds wherever the answer is going, in the workspace's language. */
  private final String common;

  /**
   * The rule that only holds in a group: notifying everybody is a thing a group has to allow, and a
   * card that tries it where it is not allowed fails to send outright — the answer is lost, not
   * merely unstyled. Left out of a direct message, where there is nobody to notify but the one
   * person reading.
   */
  private final String groupOnly;

  /**
   * Read when the context starts rather than per run, the way {@link FeishuGuides} reads its own:
   * the text is the same every run, and a translation left out of the jar then fails the deployment
   * that is missing it instead of quietly serving English on every card for weeks.
   *
   * <p>Annotated because there are two constructors here: with more than one declared and none
   * annotated, Spring picks neither and the context fails to start.
   *
   * @param properties for {@code app.feishu.locale}, the language this surface speaks
   */
  @Autowired
  public FeishuReplyFormat(final FeishuProperties properties) {
    this(properties.locale());
  }

  /** The same, for a caller that has the locale rather than the properties. */
  FeishuReplyFormat(final Locale locale) {
    this.common = LocalizedPrompt.text(FeishuGuides.LOCATION, "reply-format", locale);
    this.groupOnly = LocalizedPrompt.text(FeishuGuides.LOCATION, "reply-format-group", locale);
  }

  @Override
  public Map<String, Object> variables(final AgentRequest request) {
    final var chatType = request.chatType();
    final var group = "group".equalsIgnoreCase(chatType);
    if (!group && !"p2p".equalsIgnoreCase(chatType)) {
      // Not a Feishu run, so none of this applies to it. The same gate FeishuCardListener puts on
      // itself, and for the stronger reason: a contributor is a @Bean and is therefore asked about
      // every run in the context, including one belonging to another surface entirely. Answering
      // for that run would tell the model to write Feishu tags into an answer a browser renders
      // literally, which is the whole of what this class promises not to do. Empty rather than a
      // blank string, so core's own default fills the slot and nothing here decides for the
      // surface that owns the run.
      return Map.of();
    }
    return Map.of("replyFormat", group ? common + groupOnly : common);
  }
}
