package me.kezhenxu94.springagent.core.usermodels;

import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import reactor.core.Exceptions;

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
   * <p>So is the temperature, for the same reason: a reasoning model refuses any but its default on
   * the first call.
   *
   * @param reasoningEffort as {@link ReasoningEfforts} spells it, or null for the application's own
   *     setting
   * @param temperature as {@link Temperatures} accepts it, or null for the application's own
   * @param strictTools whether tools are offered in strict mode. The probe offers none, so this
   *     tests nothing about them; it is passed so that the client built here is the one the stored
   *     row's runs are served by
   * @return null when the endpoint answered, otherwise why it did not, in words fit to show a user
   */
  public String check(
      final String provider,
      final String baseUrl,
      final String model,
      final String token,
      final String reasoningEffort,
      final Double temperature,
      final boolean strictTools,
      final Map<String, String> headers) {
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
            .temperature(temperature)
            .strictTools(strictTools)
            .build();
    // A thread of its own with a deadline, because the configured request timeout is measured in
    // minutes — right for a reasoning turn, far too long to leave somebody waiting to be told they
    // mistyped a URL.
    final var systemMessages = systemMessagesFor(properties);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Streamed, because every run is: some gateways serve only streaming requests and refuse
      // the rest ("Stream must be set to true"), and a probe that does not stream would turn away
      // an endpoint every real message would have worked on.
      final Callable<String> ask =
          () ->
              chatClients
                  .probeClient(config, token, headers == null ? Map.of() : headers)
                  .prompt()
                  .messages(systemMessages)
                  .user("Hi")
                  .stream()
                  .content()
                  .blockLast();
      final var future = executor.submit(ask);
      try {
        future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return null;
      } catch (TimeoutException e) {
        future.cancel(true);
        return messages.get("user-model-probe-timeout", timeout.toSeconds());
      } catch (Exception e) {
        final var cause = Exceptions.unwrap(e.getCause() == null ? e : e.getCause());
        log.info("Chat model probe of {} at {} failed", model, baseUrl, cause);
        final var failure =
            cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return notFound(cause)
            ? alternativeOr(
                failure,
                config.provider(),
                baseUrl,
                model,
                token,
                reasoningEffort,
                temperature,
                strictTools,
                headers)
            : failure;
      }
    }
  }

  /**
   * Checks one of the deployment's own models the user has just switched to, the way an endpoint of
   * theirs is checked before it is saved — and where it refuses the temperature parameter the
   * deployment sends every model, which nothing else would ever stop, asks again without one and
   * records that it is to be sent none.
   *
   * @return what to tell the user, or null where the model answered and there is nothing to say
   */
  public String builtinSwitched(final UserModelRegistry registry, final String userId) {
    final var active = registry.active(userId).orElse(null);
    if (active == null
        || !UserModelRegistry.isBuiltin(active)
        || UserModelRegistry.displayName(active) == null
        || Temperatures.omitted(active.temperature())) {
      return null;
    }
    final var model = UserModelRegistry.displayName(active);
    final var failure = checkBuiltin(active, active.temperature());
    if (failure == null) {
      return null;
    }
    if (Temperatures.rejected(failure)
        && checkBuiltin(active, Temperatures.NOT_SENT) == null
        && registry.setTemperature(userId, active.name(), Temperatures.NOT_SENT)) {
      log.info("Built-in model {} refuses a temperature; {} will send it none", model, userId);
      return messages.get("user-model-temperature-omitted", model);
    }
    return messages.get("user-model-builtin-failed", model, failure);
  }

  private String checkBuiltin(final UserModelConfig row, final Double temperature) {
    return check(
        row.provider(),
        null,
        row.model(),
        null,
        row.reasoningEffort(),
        temperature,
        Boolean.TRUE.equals(row.strictTools()),
        Map.of());
  }

  /**
   * Where the endpoint answered "not found" and the provider names another API to the same
   * endpoint, whether that one answers: an endpoint can serve a model over one API and not the
   * other, and a bare "not found" sends somebody checking a URL that is right. Said rather than
   * chosen for them, since what is registered is theirs to decide.
   */
  private String alternativeOr(
      final String failure,
      final String provider,
      final String baseUrl,
      final String model,
      final String token,
      final String reasoningEffort,
      final Double temperature,
      final boolean strictTools,
      final Map<String, String> headers) {
    final var alternative = chatClients.alternativeWhenNotFound(provider);
    if (alternative == null
        || alternative.equals(provider)
        || check(
                alternative,
                baseUrl,
                model,
                token,
                reasoningEffort,
                temperature,
                strictTools,
                headers)
            != null) {
      return failure;
    }
    final var spoken = provider == null ? chatClients.defaultProvider() : provider;
    return messages.get("user-model-probe-alternative", spoken, failure, alternative);
  }

  /** Whether {@code cause} is the endpoint saying it has no such thing: an HTTP 404. */
  private static boolean notFound(final Throwable cause) {
    for (var link = cause; link != null; link = link.getCause()) {
      final var message = link.getMessage();
      if (link.getClass().getSimpleName().contains("NotFound")
          || (message != null && message.strip().startsWith("404"))) {
        return true;
      }
    }
    return false;
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
