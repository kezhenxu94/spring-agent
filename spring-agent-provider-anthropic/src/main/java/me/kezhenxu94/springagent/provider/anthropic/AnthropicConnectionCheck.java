package me.kezhenxu94.springagent.provider.anthropic;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;

/**
 * Says at startup what would otherwise be said by the first run to fail, and says it about the
 * configuration rather than about the endpoint.
 *
 * <p>There are three ways to get this wrong here and none of them fails at startup on its own.
 *
 * <ul>
 *   <li>Naming a backend nobody implements. {@code backend} is free text, so a typo — {@code
 *       vertexai}, {@code google} — quietly means "not vertex", which is to say Anthropic's own
 *       API, and the deployment then fails on a missing API key rather than on the word it
 *       mistyped.
 *   <li>Selecting Vertex with no project or no location. The SDK's own message for that names its
 *       builder field rather than the property an operator sets.
 *   <li>Naming no model. {@code AnthropicChatOptions} carries a {@code DEFAULT_MODEL} and applies
 *       it silently, so the request goes out asking for a model this deployment never chose — and
 *       on Vertex it is additionally the wrong spelling, because Vertex dates a model with
 *       {@code @} rather than with a hyphen. A 404 that names a model nobody configured reads like
 *       a broken project.
 * </ul>
 *
 * <p>Each becomes an {@code IllegalStateException} naming the property and the environment
 * variable, because those are the two things whoever deployed this can actually change. The same
 * job {@code OpenAiConnectionCheck} and {@code GoogleGenAiConnectionCheck} do for their providers.
 */
@Slf4j
@RequiredArgsConstructor
public class AnthropicConnectionCheck {

  private final AnthropicProperties properties;
  private final Environment environment;

  @PostConstruct
  void check() {
    if (!selectsAnthropic()) {
      log.info(
          "Anthropic is configured but serves nothing: {} does not name '{}'. Set it to '{}' to"
              + " route chat here",
          AnthropicProperties.CHAT_PROVIDER_PROPERTY,
          AnthropicProperties.PROVIDER,
          AnthropicProperties.PROVIDER);
      return;
    }

    final var backend = properties.backend();
    if (!AnthropicProperties.BACKEND_ANTHROPIC.equalsIgnoreCase(backend)
        && !AnthropicProperties.BACKEND_VERTEX.equalsIgnoreCase(backend)) {
      throw new IllegalStateException(
          ("%s is '%s', which is not a backend this module serves. Set it to '%s' or '%s'"
                  + " (ANTHROPIC_BACKEND).")
              .formatted(
                  AnthropicProperties.BACKEND_PROPERTY,
                  backend,
                  AnthropicProperties.BACKEND_ANTHROPIC,
                  AnthropicProperties.BACKEND_VERTEX));
    }

    if (!AnthropicProperties.configured(
        environment.getProperty(AnthropicProperties.CHAT_MODEL_PROPERTY))) {
      throw new IllegalStateException(
          ("%s names '%s' but %s is not set, and Spring AI would silently substitute a default"
                  + " model this deployment never chose. Set ANTHROPIC_CHAT_MODEL.%s")
              .formatted(
                  AnthropicProperties.CHAT_PROVIDER_PROPERTY,
                  AnthropicProperties.PROVIDER,
                  AnthropicProperties.CHAT_MODEL_PROPERTY,
                  properties.vertexBacked()
                      ? " On the Vertex backend, take the id from the model's Model Garden card"
                          + " rather than from Anthropic's docs: older ones are dated with '@'"
                          + " (claude-sonnet-4-5@20250929) and newer ones are undated"
                          + " (claude-opus-5)."
                      : ""));
    }

    if (properties.vertexBacked()) {
      checkVertex();
      return;
    }

    if (!AnthropicProperties.configured(
        environment.getProperty(AnthropicProperties.API_KEY_PROPERTY))) {
      throw new IllegalStateException(
          ("%s names '%s' on the '%s' backend but %s is not set. Set ANTHROPIC_API_KEY, or set %s"
                  + " to '%s' to be served by a Google Cloud project instead.")
              .formatted(
                  AnthropicProperties.CHAT_PROVIDER_PROPERTY,
                  AnthropicProperties.PROVIDER,
                  AnthropicProperties.BACKEND_ANTHROPIC,
                  AnthropicProperties.API_KEY_PROPERTY,
                  AnthropicProperties.BACKEND_PROPERTY,
                  AnthropicProperties.BACKEND_VERTEX));
    }
  }

  private void checkVertex() {
    final var vertex = properties.vertex();
    // The same refusal the chat model's own bean makes, and deliberately the same method: that bean
    // is built first, so in an incomplete deployment this line is never reached — it is here so the
    // check still covers the case where no model was built at all.
    vertex.requireComplete();
    log.info(
        "Claude is served by Vertex AI: project {}, location {}, credentials from {}",
        vertex.projectId(),
        vertex.location(),
        AnthropicProperties.configured(vertex.credentialsLocation())
            ? vertex.credentialsLocation()
            : "Application Default Credentials");
  }

  private boolean selectsAnthropic() {
    return AnthropicProperties.PROVIDER.equalsIgnoreCase(
        environment.getProperty(AnthropicProperties.CHAT_PROVIDER_PROPERTY));
  }
}
