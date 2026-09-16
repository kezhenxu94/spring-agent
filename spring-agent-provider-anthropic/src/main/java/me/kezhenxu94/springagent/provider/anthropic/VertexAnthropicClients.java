package me.kezhenxu94.springagent.provider.anthropic;

import com.anthropic.backends.Backend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.client.AnthropicClientAsyncImpl;
import com.anthropic.client.AnthropicClientImpl;
import com.anthropic.core.ClientOptions;
import com.anthropic.vertex.backends.VertexBackend;
import com.google.auth.oauth2.GoogleCredentials;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.ai.anthropic.http.okhttp.SpringAiAnthropicHttpClient;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

/**
 * The pair of {@link AnthropicClient}s that talk to a Google Cloud project instead of to Anthropic.
 *
 * <p>This is the whole of what the Vertex backend costs. Spring AI's {@code
 * SpringAiAnthropicHttpClient} drives a {@link Backend} entirely through that interface — {@code
 * prepareRequest} to rewrite the URL and the body, {@code authorizeRequest} to sign it, {@code
 * baseUrl()} for the host — so the Anthropic SDK's own {@link VertexBackend} slots in unmodified
 * and everything above it, from {@code AnthropicChatModel} down to the streaming envelope and the
 * tool protocol, is Spring AI's code serving Vertex without knowing it.
 *
 * <p>{@code AnthropicSetup} is not reusable here, which is why this class exists at all: it hard
 * wires {@code AnthropicBackend} and its API-key credential step. Everything below mirrors what it
 * does, minus that step.
 *
 * <p><b>A pair, not one, and that is the trap this class exists to avoid.</b> {@code
 * AnthropicChatModel}'s constructor defaults each of its two clients independently:
 *
 * <pre>{@code
 * this.anthropicClient      = requireNonNullElseGet(anthropicClient,      () -> AnthropicSetup.setupSyncClient(...));
 * this.anthropicClientAsync = requireNonNullElseGet(anthropicClientAsync, () -> AnthropicSetup.setupAsyncClient(...));
 * }</pre>
 *
 * <p>Both fallbacks build an {@code AnthropicBackend} from the options' own {@code apiKey} and
 * {@code baseUrl}, and {@code AnthropicSetup} fills those in from {@code ANTHROPIC_API_KEY} and
 * {@code ANTHROPIC_BASE_URL} in the <em>process</em> environment when they are blank. So a model
 * given only the synchronous client sends non-streaming calls to Vertex and <b>every streaming call
 * — which is what a run actually uses — to {@code api.anthropic.com}</b>, authenticated with
 * whatever the host happens to export. That failure does not look like a failure on a developer
 * machine: it looks like a working agent billed to the wrong account. {@code
 * VertexAnthropicClientsTest} asserts client identity, rather than non-nullity, for exactly this
 * reason.
 *
 * <p>Three further details are load-bearing and none of them is visible from the outside:
 *
 * <ul>
 *   <li><b>The base URL is never set on {@link ClientOptions}.</b> The SDK's services copy {@code
 *       clientOptions.baseUrl()} onto every request, and Spring AI's HTTP client only falls back to
 *       {@code backend.baseUrl()} when the request carries none. Setting one here — even the right
 *       one — would pin the host and defeat the backend's own region-aware choice between {@code
 *       *-aiplatform.googleapis.com} and the {@code eu}/{@code us} multi-region endpoints.
 *   <li><b>No credentials are put on {@link ClientOptions} either.</b> Doing so makes the SDK wrap
 *       the HTTP client in an authorizing one that would resolve a token of its own and apply
 *       {@code https://api.anthropic.com} as a default base URL. {@code VertexBackend} signs in
 *       {@code authorizeRequest}, downstream of all that, so leaving the credential unset is what
 *       keeps our client the one that runs.
 *   <li><b>The credentials are scoped explicitly.</b> Application Default Credentials from a
 *       service account arrive unscoped, and refreshing an unscoped credential fails with a message
 *       about scopes rather than about Vertex. User credentials from {@code gcloud} ignore the
 *       call.
 * </ul>
 */
final class VertexAnthropicClients {

  /**
   * The one scope Vertex asks for. Named here rather than taken from a Google constant so that the
   * reason it is applied stays beside it.
   */
  private static final String CLOUD_PLATFORM_SCOPE =
      "https://www.googleapis.com/auth/cloud-platform";

  /**
   * What a request is given when {@code spring.ai.anthropic.timeout} says nothing.
   *
   * <p>Not the SDK's own default, which is 60 seconds and is wrong for this project: a run here
   * streams a long answer and calls tools in between, and a minute is routinely less than that
   * takes. The symptom of the SDK's default is a run that dies partway through with a read timeout
   * and no explanation from the endpoint. The applications set the property explicitly — the same
   * 30 minutes the OpenAI block uses — and this is the floor under a deployment that does not.
   */
  private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

  /**
   * What the SDK itself retries; named so that "nothing configured" is a decision rather than a 0.
   */
  private static final int DEFAULT_MAX_RETRIES = 2;

  private VertexAnthropicClients() {}

  /**
   * @param vertex the project, region and optional key file
   * @param configuredTimeout the per-request timeout, from {@code spring.ai.anthropic.timeout}, or
   *     null where nothing is configured
   * @param configuredMaxRetries from {@code spring.ai.anthropic.max-retries}, or null
   * @param customHeaders from {@code spring.ai.anthropic.custom-headers}; a gateway or a proxy in
   *     front of Vertex may need one, and honouring the same knob on both backends is what keeps
   *     the two paths configurable the same way
   */
  static Clients create(
      final AnthropicProperties.Vertex vertex,
      final Duration configuredTimeout,
      final Integer configuredMaxRetries,
      final Map<String, String> customHeaders,
      final ObservationRegistry observationRegistry,
      final MeterRegistry meterRegistry) {

    // Both are null when nothing is configured — Spring AI's properties class defaults neither —
    // and the SDK's builders reject null rather than defaulting, being Kotlin.
    final var timeout = configuredTimeout == null ? DEFAULT_TIMEOUT : configuredTimeout;
    final var maxRetries =
        configuredMaxRetries == null ? DEFAULT_MAX_RETRIES : configuredMaxRetries;

    // Before the SDK's builder, whose own message for a missing project names a Kotlin field rather
    // than the variable an operator sets.
    vertex.requireComplete();

    final var backend =
        VertexBackend.builder()
            .googleCredentials(credentials(vertex.credentialsLocation()))
            .project(vertex.projectId())
            .region(vertex.location())
            .build();

    final var httpClient =
        SpringAiAnthropicHttpClient.builder()
            .backend(backend)
            .timeout(timeout)
            .observationRegistry(observationRegistry)
            .meterRegistry(meterRegistry)
            // The one place the interceptor can be attached on this path. AnthropicChatModel's
            // builder refuses httpClientBuilderCustomizers beside a pre-built client — by then the
            // HTTP layer already exists — so the bean mechanism Spring AI uses on the other backend
            // is unavailable here and the interceptor goes on directly.
            .interceptor(new AnthropicErrorBodyLoggingInterceptor())
            .build();

    final var options =
        ClientOptions.builder().timeout(timeout).maxRetries(maxRetries).httpClient(httpClient);
    // The same User-Agent Spring AI's own client sends, so a request through this path is
    // attributable to Spring AI in Vertex's logs exactly as one through the other path is.
    options.putHeader("User-Agent", "spring-ai-anthropic-sdk");
    if (customHeaders != null) {
      customHeaders.forEach(options::putHeader);
    }

    // One ClientOptions, two views of it. The SDK's two implementations are thin wrappers over the
    // same options and the same HTTP client, so the Vertex backend is prepared once per request
    // rather than once per client — which matters, because VertexBackend refuses a request it has
    // already rewritten.
    final var built = options.build();
    return new Clients(new AnthropicClientImpl(built), new AnthropicClientAsyncImpl(built));
  }

  /**
   * Both clients {@code AnthropicChatModel} needs, so that a caller cannot pass one and forget the
   * other — which is the whole failure this record exists to make unrepresentable.
   */
  record Clients(AnthropicClient sync, AnthropicClientAsync async) {}

  /**
   * Application Default Credentials unless a location names a key file.
   *
   * <p>ADC is the normal case and covers the three ways a deployment usually proves itself: {@code
   * gcloud auth application-default login} on a laptop, {@code GOOGLE_APPLICATION_CREDENTIALS}
   * pointing at a file, and the metadata server on GKE, Cloud Run or GCE. A location is for the
   * deployment that has none of those — a container image handed a mounted secret, say.
   *
   * <p>A Spring resource location rather than a path, so {@code classpath:} works in a test and
   * {@code file:} is explicit in a deployment.
   */
  private static GoogleCredentials credentials(final String location) {
    try {
      final var credentials =
          AnthropicProperties.configured(location)
              ? fromResource(location)
              : GoogleCredentials.getApplicationDefault();
      // A no-op on credentials that already carry scopes or do not take them (a user credential
      // from gcloud); the case it exists for is a service-account key, which arrives unscoped and
      // fails to refresh with a message that names neither Vertex nor this deployment.
      return credentials.createScoped(List.of(CLOUD_PLATFORM_SCOPE));
    } catch (final IOException e) {
      throw new UncheckedIOException(
          "Could not obtain Google credentials for the Vertex backend. Either set "
              + AnthropicProperties.PREFIX
              + ".vertex.credentials-location to a service account key, or make Application"
              + " Default Credentials available (gcloud auth application-default login,"
              + " GOOGLE_APPLICATION_CREDENTIALS, or a workload identity).",
          e);
    }
  }

  private static GoogleCredentials fromResource(final String location) throws IOException {
    final ResourceLoader loader = new DefaultResourceLoader();
    try (final InputStream stream = loader.getResource(location).getInputStream()) {
      return GoogleCredentials.fromStream(stream);
    }
  }
}
