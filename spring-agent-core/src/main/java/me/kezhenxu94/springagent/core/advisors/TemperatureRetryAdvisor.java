package me.kezhenxu94.springagent.core.advisors;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.usermodels.Temperatures;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * Asks the model once more without a temperature where it refused the request over the temperature
 * parameter itself — a 400 naming it deprecated or unsupported.
 *
 * <p>The deployment's temperature is copied onto every request, to its own models and to rows that
 * set none, and some models refuse the parameter whatever its value. A row can say to send none
 * ({@link Temperatures#NOT_SENT}), and switching to one of the deployment's models finds that out;
 * this is the net under both, for a model nobody has found out about yet. Nothing else is read as a
 * reason to retry, and the retry is made at most once per call: what it sends carries no
 * temperature, so a second refusal is about something else.
 *
 * <p>Last before the model, inside the tool loop, so it is one model call that is retried — not the
 * turn, whose tool calls have already run — and only where nothing of the answer has been streamed
 * yet, since a reply cannot be taken back once part of it is on a surface.
 */
@Slf4j
public final class TemperatureRetryAdvisor implements CallAdvisor, StreamAdvisor {

  public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

  @Override
  public String getName() {
    return "TemperatureRetryAdvisor";
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public ChatClientResponse adviseCall(
      final ChatClientRequest request, final CallAdvisorChain chain) {
    final var retry = chain.copy(this);
    try {
      return chain.nextCall(request);
    } catch (RuntimeException e) {
      final var without = withoutTemperature(request, e);
      if (without == null) {
        throw e;
      }
      return retry.nextCall(without);
    }
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(
      final ChatClientRequest request, final StreamAdvisorChain chain) {
    final var retry = chain.copy(this);
    final var emitted = new AtomicBoolean();
    return chain
        .nextStream(request)
        .doOnNext(response -> emitted.set(true))
        .onErrorResume(
            error -> {
              final var without = emitted.get() ? null : withoutTemperature(request, error);
              return without == null ? Flux.error(error) : retry.nextStream(without);
            });
  }

  /**
   * {@code request} without a temperature, where {@code error} is the model refusing one and the
   * request carried one; null otherwise, which means the error stands.
   */
  static ChatClientRequest withoutTemperature(
      final ChatClientRequest request, final Throwable error) {
    final var options = request.prompt().getOptions();
    if (options == null || options.getTemperature() == null || !Temperatures.rejected(error)) {
      return null;
    }
    log.warn(
        "The model refused the request over its temperature ({}); asking once more without one",
        error.getMessage());
    return request
        .mutate()
        .prompt(
            request
                .prompt()
                .mutate()
                .chatOptions(options.mutate().temperature(null).build())
                .build())
        .build();
  }
}
