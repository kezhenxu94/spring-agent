package me.kezhenxu94.springagent.provider.anthropic;

import com.anthropic.errors.AnthropicServiceException;
import java.util.Optional;
import me.kezhenxu94.springagent.core.agent.ProviderRejection;

/**
 * What Anthropic — or a Vertex project serving Claude — said when it refused, read off the SDK's
 * own exception.
 *
 * <p>By the time a failure reaches {@code SpringAgent} an advisor has rewrapped it, so the stack
 * trace names neither the status nor the reason. {@link AnthropicServiceException} is the base of
 * every status-carrying subclass the SDK throws ({@code BadRequestException}, {@code
 * RateLimitException}, {@code PermissionDeniedException} and the rest), so matching on it covers
 * all of them at once.
 *
 * <p>The error type is worth printing beside the status because the pair is what distinguishes the
 * cases an operator acts on differently: {@code overloaded_error} on 529 is Anthropic asking for a
 * retry, while {@code invalid_request_error} on the same run is this deployment's bug. On the
 * Vertex backend a 403 usually means the project has no entitlement for the model rather than that
 * the credential is wrong, and the body is where that is said.
 *
 * <p>Answers about {@code AnthropicServiceException} and nothing else, so a timeout, a DNS failure,
 * a missing Google credential or a bug in this module falls through to core's own logging rather
 * than being dressed up as a rejection. Unlike the OpenAI provider there is no interceptor beside
 * this one: that SDK renders a non-JSON error envelope as the bare word {@code Unknown} and needs
 * one to rescue the body, whereas this exception carries the parsed body already.
 */
public class AnthropicProviderRejection implements ProviderRejection {

  @Override
  public Optional<String> describe(final Throwable error) {
    if (!(error instanceof AnthropicServiceException rejected)) {
      return Optional.empty();
    }
    return Optional.of(
        "status=%s, request-id=%s, type=%s, body=%s"
            .formatted(
                rejected.statusCode(),
                rejected.headers().values("request-id"),
                rejected.errorType().map(Object::toString).orElse("unknown"),
                rejected.body()));
  }
}
