package me.kezhenxu94.springagent.core.usermodels;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;

/**
 * Asks an endpoint one very small question, to find out whether it works before anything is stored
 * against it.
 *
 * <p>Shared by the tool that registers a model and the card that does, so that "tested before
 * saving" means the same thing whichever way a user got there.
 *
 * <p>A real completion rather than a listing of the endpoint's models: it tests the base URL, the
 * token and the model name in the one call, which is the combination that has to work, and not
 * every OpenAI-compatible gateway serves {@code /models} at all.
 */
@Slf4j
@RequiredArgsConstructor
public class UserModelProbe {

  private final UserChatClients chatClients;
  private final CoreMessages messages;
  private final Duration timeout;

  /**
   * @return null when the endpoint answered, otherwise why it did not, in words fit to show a user
   */
  public String check(final String baseUrl, final String model, final String token) {
    final var config =
        UserModelConfig.builder().name("probe").baseUrl(baseUrl).model(model).build();
    // A thread of its own with a deadline, because the configured request timeout is measured in
    // minutes — right for a reasoning turn, far too long to leave somebody waiting to be told they
    // mistyped a URL.
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final Callable<String> ask =
          () -> chatClients.probeClient(config, token).prompt().user("Hi").call().content();
      final var future = executor.submit(ask);
      try {
        future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return null;
      } catch (TimeoutException e) {
        future.cancel(true);
        return messages.get("user-model-probe-timeout", timeout.toSeconds());
      } catch (Exception e) {
        final var cause = e.getCause() == null ? e : e.getCause();
        log.info("Chat model probe of {} at {} failed", model, baseUrl, cause);
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
      }
    }
  }
}
