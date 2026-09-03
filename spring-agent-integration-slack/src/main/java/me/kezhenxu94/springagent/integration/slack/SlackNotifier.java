package me.kezhenxu94.springagent.integration.slack;

import com.slack.api.methods.MethodsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.notify.Notifier;
import me.kezhenxu94.springagent.core.observing.Route;
import org.springframework.stereotype.Component;

/**
 * Says something to a Slack channel with no run behind it — this deployment's implementation of
 * {@link Notifier}.
 *
 * <p>What it carries is either text this codebase wrote about its own workings or a run's answer
 * arriving from another surface — see {@link Notifier} for both cases. Nothing from an event
 * payload reaches here, and anything a person wrote goes through {@link #quoted(String)} on the
 * way.
 *
 * <p>Deliberately independent of a run, a request and the model. Its whole reason for existing is
 * the case where one of those is what failed — see {@link Notifier} — so it holds nothing but the
 * client and takes no tool context.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackNotifier implements Notifier {

  private final MethodsClient slack;

  @Override
  public void send(final Route route, final String text) {
    if (route == null || route.isEmpty()) {
      // A deployment that configured nowhere to send this wanted nowhere, and a caller should not
      // have to know which surface is installed to work that out.
      return;
    }
    final var target = route.chatId();
    try {
      // Plain text rather than blocks: this is one sentence about a run that failed, and a message
      // that has to be built out of Block Kit to say it is a message that can fail to be said.
      final var response = slack.chatPostMessage(r -> r.channel(target).text(text));
      if (!response.isOk()) {
        throw new IllegalStateException(
            "Could not send a notification to " + target + ": " + response.getError());
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Could not send a notification to " + target, e);
    }
    log.info("Sent a notification to {}", target);
  }

  @Override
  public String surface() {
    return "slack";
  }

  /**
   * {@inheritDoc}
   *
   * <p>Slack's own three, and only those three: {@code &}, {@code <} and {@code >}. That is the
   * whole of what its escaping rules ask for, and it is enough, because everything here that acts
   * rather than renders is written as an angle-bracket token — {@code <!channel>} and {@code
   * <!here>} notify a channel, {@code <@U123ABC>} notifies a person. Escaping the brackets takes
   * all of them at once. The mrkdwn emphasis characters are deliberately left alone: unlike
   * Feishu's tags they only ever change how a run of text looks, and a quoted message that renders
   * an asterisk as bold is a cosmetic surprise rather than somebody else's notification.
   *
   * @see <a href="https://api.slack.com/reference/surfaces/formatting#escaping">Escaping text</a>
   */
  @Override
  public String quoted(final String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    // Ampersands first, or the ampersands the next two write would themselves be escaped and the
    // reader would see &lt; where a < was meant.
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
