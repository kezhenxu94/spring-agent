package me.kezhenxu94.springagent.core.usermodels;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

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
 *
 * <p>The call carries the deployment's own system prompt, shaped exactly as {@code
 * SpringAgent.systemMessagesFor} shapes it — one {@code SystemMessage} where {@code
 * app.ai.system-prompt-parts} is empty, one per part otherwise — rather than the user's turn on its
 * own. Unrendered, because a probe has no request to pull prompt variables from and the wire format
 * is what is under test, not the wording. That format is exactly what an Anthropic BYOM row risks
 * getting wrong: with {@code app.ai.anthropic.chat.options.cache-options} on, several system
 * messages become an array of {@code TextBlockParam}s with a {@code cache_control} entry rather
 * than one joined string, and not every Anthropic-compatible gateway accepts that shape. A probe
 * sending only the user's "Hi" never reaches that code path, so a gateway that rejects the array
 * would pass the probe and then fail the first real message.
 */
@Slf4j
@RequiredArgsConstructor
public class UserModelProbe {

  private final UserChatClients chatClients;
  private final CoreMessages messages;
  private final Duration timeout;
  private final SpringAgentProperties properties;

  /**
   * The reasoning effort is part of what is tested, not a detail applied afterwards: a gateway that
   * rejects {@code reasoning_effort} outright rejects it on the very first call, so probing without
   * it would store an endpoint that then fails on every real message.
   *
   * @param reasoningEffort as {@link ReasoningEfforts} spells it, or null for the application's own
   *     setting
   * @return null when the endpoint answered, otherwise why it did not, in words fit to show a user
   */
  public String check(
      final String provider,
      final String baseUrl,
      final String model,
      final String token,
      final String reasoningEffort) {
    final var config =
        UserModelConfig.builder()
            .name("probe")
            // The row is not stored, but it has to name the protocol: the whole point of a probe is
            // to test what will actually be spoken to the endpoint, and on a deployment carrying
            // two providers that is a choice rather than a given.
            .provider(provider == null || provider.isBlank() ? null : provider.trim())
            .baseUrl(baseUrl)
            .model(model)
            .reasoningEffort(ReasoningEfforts.normalize(reasoningEffort))
            .build();
    // A thread of its own with a deadline, because the configured request timeout is measured in
    // minutes — right for a reasoning turn, far too long to leave somebody waiting to be told they
    // mistyped a URL.
    final var systemMessages = systemMessagesFor(properties);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final Callable<String> ask =
          () ->
              chatClients
                  .probeClient(config, token)
                  .prompt()
                  .messages(systemMessages)
                  .user("Hi")
                  .call()
                  .content();
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

  /**
   * The same shape {@code SpringAgent.systemMessagesFor} builds — one {@link SystemMessage} per
   * {@code app.ai.system-prompt-parts} element, or the whole {@code app.ai.system-prompt} as one
   * where none are configured — but never rendered: a probe has no request to draw prompt variables
   * from, and the wire shape under test does not depend on the wording.
   *
   * <p>Package-private and static so the shape can be asserted without a probe call, which is where
   * it becomes invisible.
   */
  static List<Message> systemMessagesFor(final SpringAgentProperties properties) {
    final var parts = properties.ai().systemPromptParts();
    if (parts.isEmpty()) {
      return List.of(SystemMessage.builder().text(properties.ai().systemPrompt()).build());
    }
    return parts.stream().<Message>map(part -> SystemMessage.builder().text(part).build()).toList();
  }
}
