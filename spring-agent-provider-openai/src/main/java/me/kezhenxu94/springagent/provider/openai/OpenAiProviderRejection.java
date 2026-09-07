package me.kezhenxu94.springagent.provider.openai;

import com.openai.errors.OpenAIServiceException;
import java.util.Optional;
import me.kezhenxu94.springagent.core.agent.ProviderRejection;

/**
 * What an OpenAI-compatible endpoint said when it refused, read off the SDK's own exception.
 *
 * <p>Three things, none of which survives into the stack trace core sees: the status, the handle a
 * gateway operator searches by, and the body. The body is the one that matters and the one the SDK
 * destroys — see {@link OpenAiErrorBodyLoggingInterceptor}, which is why this deployment can read
 * it at all.
 *
 * <p>Answers about {@code OpenAIServiceException} and nothing else, so a failure that is a timeout,
 * a DNS error or a bug here falls through to core's own logging rather than being dressed up as a
 * rejection.
 */
public class OpenAiProviderRejection implements ProviderRejection {

  @Override
  public Optional<String> describe(final Throwable error) {
    if (!(error instanceof OpenAIServiceException rejected)) {
      return Optional.empty();
    }
    return Optional.of(
        "status=%s, request-id=%s, body=%s"
            .formatted(
                rejected.statusCode(), rejected.headers().values("x-request-id"), rejected.body()));
  }
}
