package me.kezhenxu94.springagent.provider.googlegenai;

import com.google.genai.errors.ApiException;
import java.util.Optional;
import me.kezhenxu94.springagent.core.agent.ProviderRejection;

/**
 * What a Gemini endpoint said when it refused, read off the SDK's own exception.
 *
 * <p>By the time a failure reaches {@code SpringAgent} an advisor has rewrapped it as {@code
 * IllegalStateException("Stream processing failed")}, so the stack trace names neither the status
 * nor the reason. {@link ApiException} carries all three things worth logging — the HTTP code,
 * Google's own status string ({@code INVALID_ARGUMENT}, {@code RESOURCE_EXHAUSTED}, {@code
 * PERMISSION_DENIED}) and the message — and the status is the useful one here: it is the difference
 * between a quota problem and a malformed request, which the code alone does not say.
 *
 * <p>Answers about {@code ApiException} and nothing else, so a timeout, a DNS failure or a bug in
 * this module falls through to core's own logging rather than being dressed up as a rejection.
 * {@code ClientException} and {@code ServerException} both extend it, so both are covered; {@code
 * GenAiIOException} deliberately is not, because a socket that closed is not something the endpoint
 * said.
 *
 * <p>Unlike the OpenAI provider there is no interceptor beside this one. openai-java needs one
 * because it renders a non-JSON error envelope as the bare word {@code Unknown}, destroying the
 * body; this SDK builds its exception from the response body and keeps the message, so there is
 * nothing to rescue.
 */
public class GoogleGenAiProviderRejection implements ProviderRejection {

  @Override
  public Optional<String> describe(final Throwable error) {
    if (!(error instanceof ApiException rejected)) {
      return Optional.empty();
    }
    return Optional.of(
        "code=%s, status=%s, message=%s"
            .formatted(rejected.code(), rejected.status(), rejected.message()));
  }
}
