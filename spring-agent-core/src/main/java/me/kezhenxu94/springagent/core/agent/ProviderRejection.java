package me.kezhenxu94.springagent.core.agent;

import java.util.Optional;

/**
 * Reads what a model endpoint said when it refused a run, so that the refusal can be logged beside
 * the id of the run it refused.
 *
 * <p>The one thing about a provider that core cannot get from a Spring AI interface. By the time a
 * failure reaches {@link SpringAgent}, an advisor has rewrapped it as {@code
 * IllegalStateException("Stream processing failed")}, and the SDK exception underneath renders a
 * non-JSON body as the word {@code Unknown} — so the stack trace names neither the status, nor the
 * gateway's own request id, nor the body. Each of those lives on a provider's own exception type,
 * under a different name per provider, and Spring AI does not normalise them.
 *
 * <p>Which is why this is not "throw a better exception": the value is in the correlation. A
 * provider's HTTP layer can and does log the body itself — see {@code
 * OpenAiErrorBodyLoggingInterceptor} — but it does so on whichever thread the call happened to run
 * on and knows nothing about the run. This interface is what lets core put the two together on one
 * line.
 *
 * <p>Implementations are asked about every failure of every run, including the many that are not
 * rejections at all, so returning empty must be cheap and must never throw. Every bean of this type
 * is asked, and the first non-empty answer is the one logged.
 */
public interface ProviderRejection {

  /**
   * What the endpoint said, ready to be logged, or empty where {@code error} is not a rejection
   * this provider recognises.
   *
   * @param error one link of a failed run's cause chain, not the chain — core walks it
   */
  Optional<String> describe(Throwable error);
}
