package me.kezhenxu94.springagent.integration.slack.handler;

import com.slack.api.methods.MethodsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import org.springframework.stereotype.Component;

/**
 * Takes a question form off the message that carries it, outside any run.
 *
 * <p>Its own class because of when it is called rather than what it does: the run that put the form
 * up has ended by the time any of this happens — possibly in another process — so there is no
 * updater to ask and no message object to reuse. All it has is the row, which records the message
 * the form was posted as.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackQuestionFormCloser {

  private final MethodsClient slack;
  private final SlackMessages messages;

  /** A later message overtook the question, so the form is replaced by a line saying so. */
  public void superseded(final PendingQuestion pending) {
    close(pending, messages.get("question-superseded"));
  }

  /** The question was answered, so the form is replaced by what was chosen. */
  public void answered(final PendingQuestion pending, final String summary) {
    close(pending, summary);
  }

  private void close(final PendingQuestion pending, final String text) {
    // The run that drew the form has ended, so nothing is going to render this message again and
    // replacing it wholesale is safe. Were the run still going, its next streaming write would put
    // the form back — which is why closing a form is only ever done after the row behind it has
    // been moved out of PENDING.
    if (pending.cardId() == null || pending.chatId() == null) {
      return;
    }
    try {
      final var response =
          slack.chatUpdate(
              r ->
                  r.channel(pending.chatId())
                      .ts(pending.cardId())
                      // Blocks cleared explicitly rather than left out: chat.update leaves a field
                      // it is not given exactly as it was, so a call carrying only `text` would
                      // replace nothing and the form would stay pressable.
                      .blocks(java.util.List.of())
                      .text(text));
      if (!response.isOk()) {
        log.warn("Could not close the question form for {}: {}", pending.id(), response.getError());
      }
    } catch (Exception e) {
      log.warn("Could not close the question form for {}", pending.id(), e);
    }
  }
}
