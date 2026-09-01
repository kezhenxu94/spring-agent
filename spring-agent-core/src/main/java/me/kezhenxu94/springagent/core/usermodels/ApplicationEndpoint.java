package me.kezhenxu94.springagent.core.usermodels;

import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The application's own chat options with the connection it actually dials filled in.
 *
 * <p>Everything in this package builds a chat client of its own — {@link BuiltinModels} to ask an
 * endpoint what it serves, {@link UserChatClients} to reach a model a user chose — and each starts
 * from the application's options so that the result is the deployment's endpoint with one or two
 * fields changed. That only works if those options say where the endpoint is, and they do not:
 * {@code OpenAiChatProperties.toOptions()}, which is what the {@code OpenAiChatModel} bean is built
 * with, copies the sampling parameters and nothing else. Base URL, API key, timeout, retries, proxy
 * and custom headers live only in the {@code spring.ai.openai.*} connection properties, from which
 * Spring AI's auto-configuration builds the model's HTTP client directly. So {@code
 * chatModel.getOptions().getApiKey()} is null in every deployment, however it is configured.
 *
 * <p>What that null costs is not a clear failure. The OpenAI SDK falls back to the {@code
 * OPENAI_BASE_URL} and {@code OPENAI_API_KEY} environment variables it reads on its own, so a
 * deployment that happens to name its variables that gets a working client by coincidence, and one
 * that names them anything else — or writes the values into its configuration file — gets either
 * {@code IllegalStateException: At least one credential source must be specified} or, worse, a
 * client silently pointed at the public OpenAI endpoint with somebody else's key. The timeout is
 * the same story more quietly: dropped, a user's client gets the SDK's 60 seconds where the
 * deployment configured minutes, and long turns die mid-stream.
 *
 * <p>Resolved with the same helper the auto-configuration uses, so the connection this package
 * dials and the one the application's own model dials cannot disagree — including the precedence
 * rule that a value under {@code spring.ai.openai.chat} wins over the one beside it, and that an
 * explicitly empty API key means no-auth rather than "not configured".
 */
public final class ApplicationEndpoint {

  private ApplicationEndpoint() {}

  /**
   * {@code options} — the {@code OpenAiChatModel} bean's own — with the connection written over it.
   *
   * <p>Takes the options rather than the model so that this can be asserted without building one,
   * which needs a credential the very deployment this method exists for does not put there.
   *
   * <p>The model is deliberately not among the fields written over: it is what {@code toOptions()}
   * already carries, and it is the options — not the connection properties — that decide which
   * model a request names, down to the SDK's own default where nothing was configured. Taking it
   * from the resolved properties instead would have this package ask for a different model than the
   * application's own runs do.
   */
  public static OpenAiChatOptions resolve(
      final OpenAiChatOptions options,
      final OpenAiCommonProperties commonProperties,
      final OpenAiChatProperties chatProperties) {
    final var resolved =
        OpenAiAutoConfigurationUtil.resolveCommonProperties(commonProperties, chatProperties);
    return options
        .mutate()
        .baseUrl(resolved.getBaseUrl())
        .apiKey(resolved.getApiKey())
        .credential(resolved.getCredential())
        .organizationId(resolved.getOrganizationId())
        .deploymentName(resolved.getMicrosoftDeploymentName())
        .microsoftFoundryServiceVersion(resolved.getMicrosoftFoundryServiceVersion())
        .microsoftFoundry(resolved.isMicrosoftFoundry())
        .gitHubModels(resolved.isGitHubModels())
        .timeout(resolved.getTimeout())
        .maxRetries(resolved.getMaxRetries())
        .proxy(resolved.getProxy())
        .customHeaders(resolved.getCustomHeaders())
        .build();
  }
}
