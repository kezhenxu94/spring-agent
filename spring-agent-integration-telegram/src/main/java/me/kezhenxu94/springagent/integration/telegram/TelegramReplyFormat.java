package me.kezhenxu94.springagent.integration.telegram;

import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.PromptVariablesContributor;
import me.kezhenxu94.springagent.core.config.LocalizedPrompt;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What the model has to know to write an answer that arrives looking right in Telegram.
 *
 * <p>Much shorter than Slack's, because the model is not asked to write a dialect: it writes the
 * markdown it already writes, and {@code TelegramMarkdown} translates. What is left to say is what
 * the translation cannot fix — that there are no tables, that a heading is only a bold line, that
 * an image is not shown inline — and who a handle notifies.
 *
 * <p>Filled for every run, as Slack's is, and that includes runs that did not come from a message —
 * a triage run speaks through {@code TelegramSendMessage}, which renders markdown the same way.
 * Safe only because this is the one chat surface on the classpath; beside a second one it would
 * have to ask whose run it was, which is the trap {@code FeishuReplyFormat} fell into beside the
 * browser surface.
 */
@Component
public class TelegramReplyFormat implements PromptVariablesContributor {

  static final String LOCATION = "telegram/prompts/";

  private final String common;

  /** What only holds in a group, where there is somebody besides the reader to notify. */
  private final String groupOnly;

  @Autowired
  public TelegramReplyFormat(final TelegramProperties properties) {
    this(properties.locale());
  }

  TelegramReplyFormat(final Locale locale) {
    this.common = LocalizedPrompt.text(LOCATION, "reply-format", locale);
    this.groupOnly = LocalizedPrompt.text(LOCATION, "reply-format-group", locale);
  }

  @Override
  public Map<String, Object> variables(final AgentRequest request) {
    final var group = !"p2p".equalsIgnoreCase(request.chatType());
    return Map.of("replyFormat", group ? common + groupOnly : common);
  }
}
