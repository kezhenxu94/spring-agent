package me.kezhenxu94.springagent.provider.anthropic;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * The settings this module adds, bound from the same {@code spring.ai.anthropic.*} prefix Spring
 * AI's own Anthropic auto-configuration binds.
 *
 * <p>The same prefix rather than one of ours, for the reason {@code GoogleGenAiProperties} gives:
 * Spring AI publishes the chat model from that block, and a second block naming the same model
 * would be two places for a credential to go stale.
 *
 * <p><b>This record binds only what this module adds — {@link #backend()} and {@link
 * #vertex()}.</b> Everything else under that prefix is Spring AI's: {@code api-key}, {@code
 * base-url}, {@code timeout}, {@code max-retries}, {@code custom-headers} and the whole of {@code
 * chat.*}. Restating one here would not merely duplicate it; a relaxed binder reads both records
 * from the same properties, so the two would drift apart the moment Spring AI changed a default.
 *
 * <p>The two fields that are read rather than bound are named here as constants, because the
 * conditions, the connection check and the client builder all ask about them and must not drift.
 */
@ConfigurationProperties(prefix = AnthropicProperties.PREFIX)
public record AnthropicProperties(String backend, @NestedConfigurationProperty Vertex vertex) {

  public static final String PREFIX = "spring.ai.anthropic";

  /**
   * Which host serves Claude here: {@link #BACKEND_ANTHROPIC} or {@link #BACKEND_VERTEX}.
   *
   * <p>A backend rather than two provider modules, because the two differ in nothing this project
   * would have to write twice. The request body, the options, the streaming envelope and the tool
   * protocol are one protocol; Vertex changes the host it is sent to and how it is signed, and the
   * Anthropic SDK expresses exactly that as {@code com.anthropic.backends.Backend}.
   */
  public static final String BACKEND_PROPERTY = PREFIX + ".backend";

  /**
   * Anthropic's own API, which is Spring AI's auto-configuration and none of this module's code.
   */
  public static final String BACKEND_ANTHROPIC = "anthropic";

  /** Claude served by a Google Cloud project. See {@link VertexAnthropicClients}. */
  public static final String BACKEND_VERTEX = "vertex";

  /**
   * Spring AI's, not ours — {@code spring.ai.anthropic.chat.model}. Read rather than bound so that
   * the Vertex client can refuse to start without one: {@code AnthropicChatOptions} carries a
   * {@code DEFAULT_MODEL} and applies it silently whenever nothing is configured, which on Vertex
   * asks a project for a model name Vertex does not spell that way.
   */
  public static final String CHAT_MODEL_PROPERTY = PREFIX + ".chat.model";

  /** Spring AI's credential for the {@code anthropic} backend, read only by the startup check. */
  public static final String API_KEY_PROPERTY = PREFIX + ".api-key";

  /**
   * What an operator sets to point chat at this provider. Spring AI's switch, not one of ours — see
   * this module's README — and named here because the startup check has to ask about it.
   */
  public static final String CHAT_PROVIDER_PROPERTY = "spring.ai.model.chat";

  /** The value that switch takes to mean this provider, as Spring AI spells it. */
  public static final String PROVIDER = "anthropic";

  public AnthropicProperties {
    // Blank becomes the default; anything else is kept *exactly* as written, deliberately not
    // trimmed. The Vertex chat model is gated by @ConditionalOnProperty on this same key, and that
    // condition compares the raw value — so trimming here would let " vertex " mean Vertex to this
    // record and not to the condition. The result would be a deployment served by Anthropic's API
    // while every other decision keyed on vertexBacked() believed otherwise: no key lent to a user
    // row, and no model listing. One reading of one property, or two things silently disagreeing.
    backend = configured(backend) ? backend : BACKEND_ANTHROPIC;
    vertex = vertex == null ? new Vertex(null, null, null) : vertex;
  }

  /** Whether the Vertex backend is the one selected. */
  public boolean vertexBacked() {
    return BACKEND_VERTEX.equalsIgnoreCase(backend);
  }

  /**
   * Where a Google Cloud project serves Claude, and how to prove this deployment may ask it to.
   *
   * @param projectId the GCP project holding the Vertex entitlement
   * @param location the Vertex region — this is part of the hostname, not a header, so a region
   *     that does not offer the model 404s rather than falling back
   * @param credentialsLocation an optional service-account JSON, as a Spring resource location.
   *     Blank means Application Default Credentials, which is the normal case: {@code gcloud} on a
   *     laptop, workload identity on GKE or Cloud Run. Naming a file is for deployments that have
   *     no ambient credential to find.
   */
  public record Vertex(String projectId, String location, String credentialsLocation) {

    /**
     * Refuses an incomplete Vertex block, in terms of the variables an operator sets.
     *
     * <p>Here rather than only in {@link AnthropicConnectionCheck} because of bean order: the chat
     * model is built by a configuration ordered <em>before</em> the one holding that check, so
     * without this the first thing to fail is the SDK's own {@code Check.checkRequired}, whose
     * message names its builder field — {@code project}, {@code region} — and neither the property
     * nor the variable. Both callers share this so the two messages cannot drift.
     */
    public void requireComplete() {
      if (configured(projectId) && configured(location)) {
        return;
      }
      throw new IllegalStateException(
          ("%s is '%s' but %s.vertex.project-id and .location are not both set. Set"
                  + " ANTHROPIC_VERTEX_PROJECT and ANTHROPIC_VERTEX_LOCATION; the location is part"
                  + " of the hostname rather than a header, so a region that does not serve the"
                  + " model answers 404 rather than falling back.")
              .formatted(BACKEND_PROPERTY, BACKEND_VERTEX, PREFIX));
    }
  }

  /**
   * Whether a setting holds something.
   *
   * <p>Blank rather than empty, which is the rule core's {@code ConditionalOnNonBlankProperty}
   * applies and deliberately not Guava's {@code isNullOrEmpty}: a variable somebody set to a space
   * is a variable nobody set, and a property spelled {@code ${SOME_VAR:}} is present and empty when
   * nobody set the variable at all.
   */
  public static boolean configured(final String value) {
    return value != null && !value.isBlank();
  }
}
