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
 * <p>What it carries is text this codebase wrote about its own workings; nothing a model produced
 * and nothing from an event payload reaches here.
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
}
