package me.kezhenxu94.springagent.integration.slack;

import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.PromptVariablesContributor;
import me.kezhenxu94.springagent.core.config.LocalizedPrompt;
import me.kezhenxu94.springagent.integration.slack.config.SlackProperties;
import org.springframework.beans.factory.annotation.Autowired;
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
 * mentions and notifies all of them, interrupting twenty people to answer one.
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

  /** Where this module's prompt files live, as a classpath location. */
  static final String LOCATION = "slack/prompts/";

  /**
   * Every rule that holds wherever the answer is going, in the workspace's language. Long on the
   * places Slack differs from the CommonMark the model already knows, and on what fails silently;
   * short on everything else.
   */
  private final String common;

  /**
   * The rule that only holds in a channel. Notifying everybody present is something a person feels,
   * and a channel is where there is somebody other than the reader to feel it — in a direct message
   * there is nobody to notify but the one person already reading.
   */
  private final String channelOnly;

  /**
   * Read when the context starts rather than per run: the text is the same every run, and a
   * translation left out of the jar then fails the deployment that is missing it instead of quietly
   * serving English in every answer for weeks.
   *
   * <p>A file rather than a constant for the reason core's prompts are files, and for one this
   * class makes sharper: two thousand characters of English go into the system prompt of every run
   * on this surface, so a constant here is a constant pull towards English in a workspace that
   * asked for something else — in the model's reasoning as much as in its answer.
   *
   * <p>Annotated because there are two constructors here: with more than one declared and none
   * annotated, Spring picks neither and the context fails to start.
   *
   * @param properties for {@code app.slack.locale}, the language this surface speaks
   */
  @Autowired
  public SlackReplyFormat(final SlackProperties properties) {
    this(properties.locale());
  }

  /** The same, for a caller that has the locale rather than the properties. */
  SlackReplyFormat(final Locale locale) {
    this.common = LocalizedPrompt.text(LOCATION, "reply-format", locale);
    this.channelOnly = LocalizedPrompt.text(LOCATION, "reply-format-channel", locale);
  }

  @Override
  public Map<String, Object> variables(final AgentRequest request) {
    final var channel = !"p2p".equalsIgnoreCase(request.chatType());
    return Map.of("replyFormat", channel ? common + channelOnly : common);
  }
}
