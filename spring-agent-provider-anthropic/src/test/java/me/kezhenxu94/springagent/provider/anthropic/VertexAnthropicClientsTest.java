package me.kezhenxu94.springagent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;

/**
 * That a Vertex-backed model is Vertex-backed on <em>both</em> of its clients.
 *
 * <p>This is the test the module exists to have. {@code AnthropicChatModel}'s constructor defaults
 * its synchronous and asynchronous clients independently, each falling back to {@code
 * AnthropicSetup}, which builds an {@code AnthropicBackend} pointed at Anthropic and fills its
 * credential from {@code ANTHROPIC_API_KEY} in the process environment. So a model handed only the
 * synchronous client is <b>half</b> Vertex-backed: blocking calls go to the GCP project and every
 * streaming call — which is what a run actually uses — goes to {@code api.anthropic.com}.
 *
 * <p>On a machine with no {@code ANTHROPIC_API_KEY} that is a 401 nobody can explain. On a machine
 * with one it is worse: the agent works, and the usage is billed to whoever owns that key. Neither
 * failure names Vertex, and nothing in the code reads as wrong — which is why identity is asserted
 * here rather than left to a reviewer to notice.
 */
class VertexAnthropicClientsTest {

  private static final String CREDENTIALS = "classpath:test-service-account.json";

  private VertexAnthropicClients.Clients clients() {
    return VertexAnthropicClients.create(
        new AnthropicProperties.Vertex("a-project", "us-east5", CREDENTIALS),
        null,
        null,
        Map.of(),
        ObservationRegistry.NOOP,
        null);
  }

  @Test
  @DisplayName("both clients are built, and they are the pair the model is given")
  void bothClientsReachVertex() {
    final var built = clients();
    assertThat(built.sync()).isNotNull();
    assertThat(built.async()).isNotNull();

    final var model =
        AnthropicChatModel.builder()
            .anthropicClient(built.sync())
            .anthropicClientAsync(built.async())
            .options(AnthropicChatOptions.builder().model("claude-sonnet-4-5@20250929").build())
            .build();

    // Identity, not merely non-null: a model that built its own async client would also answer
    // non-null here, and that client would be talking to Anthropic.
    assertThat(model.getAnthropicClient()).isSameAs(built.sync());
    assertThat(model.getAnthropicClientAsync()).isSameAs(built.async());
  }

  @Test
  @DisplayName("the streaming client is NOT the one the SDK would have built for itself")
  void theAsyncClientIsNotTheDefaultOne() {
    final var built = clients();

    // The mistake, written out: only the synchronous client supplied.
    final var halfWired =
        AnthropicChatModel.builder()
            .anthropicClient(built.sync())
            .options(AnthropicChatOptions.builder().model("claude-sonnet-4-5@20250929").build())
            .build();

    // It builds happily, which is the whole problem — nothing here fails, and the asynchronous
    // client is a different object entirely, pointed at Anthropic rather than at the project.
    assertThat(halfWired.getAnthropicClientAsync()).isNotSameAs(built.async());
  }

  @Test
  @DisplayName("a named service-account key is read instead of Application Default Credentials")
  void credentialsComeFromTheNamedFile() {
    // The assertion is that this does not throw: ADC is unavailable on a build machine, so if the
    // location were ignored, GoogleCredentials.getApplicationDefault() would fail here.
    assertThat(clients().sync()).isNotNull();
  }
}
