package me.kezhenxu94.springagent.core.advisors;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.StreamIdleTimeoutException;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/**
 * Asks the model once more where its stream stalled before any of the answer had arrived — see
 * {@link StreamIdleTimeoutException}.
 *
 * <p>One endpoint's stream occasionally stops mid-response while every other request on it
 * completes, and nothing upstream can fall back once a response has started; asked again, it almost
 * always answers. Only before any output, though: text, a tool call or reasoning that reached a
 * surface cannot be taken back, and a second answer would be written after the first. Then, and
 * where the retry stalls too, the error stands — a timeout, which is what a triage run's retry
 * reads as a failure that may pass.
 *
 * <p>Last before the model with {@code TemperatureRetryAdvisor}, inside the tool loop, so that it
 * is one model call that is retried.
 */
@Slf4j
public final class StreamStallRetryAdvisor implements StreamAdvisor {

  public static final int ORDER = TemperatureRetryAdvisor.ORDER - 1;

  @Override
  public String getName() {
    return "StreamStallRetryAdvisor";
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(
      final ChatClientRequest request, final StreamAdvisorChain chain) {
    final var retry = chain.copy(this);
    final var answered = new AtomicBoolean();
    return chain
        .nextStream(request)
        .doOnNext(
            response -> {
              if (carriesOutput(response)) {
                answered.set(true);
              }
            })
        .onErrorResume(
            error -> {
              if (answered.get() || !stalled(error)) {
                return Flux.error(error);
              }
              log.warn(
                  "The model's stream stalled before it answered ({}); asking once more",
                  error.getMessage());
              return retry.nextStream(request);
            });
  }

  /** Whether {@code error} is a stream going idle, anywhere in its causes. */
  static boolean stalled(final Throwable error) {
    for (var link = error; link != null; link = link.getCause()) {
      if (link instanceof StreamIdleTimeoutException) {
        return true;
      }
      if (link.getCause() == link) {
        break;
      }
    }
    return false;
  }

  /** Whether {@code response} put any of the answer on a surface: text, a tool call, reasoning. */
  static boolean carriesOutput(final ChatClientResponse response) {
    final var chat = response.chatResponse();
    if (chat == null) {
      return false;
    }
    for (final var generation : chat.getResults()) {
      final var output = generation.getOutput();
      if (output == null) {
        continue;
      }
      if ((output.getText() != null && !output.getText().isEmpty())
          || output.hasToolCalls()
          || (output.getMetadata().get("reasoningContent") instanceof String reasoning
              && !reasoning.isEmpty())) {
        return true;
      }
    }
    return false;
  }
}
