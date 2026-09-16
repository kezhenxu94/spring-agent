package me.kezhenxu94.springagent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.vertex.backends.VertexBackend;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the Vertex location really is the hostname, which is the claim the {@code location} comment
 * in every {@code application.yaml} rests on and the reason {@code spring.ai.anthropic.base-url} is
 * documented as ignored on this backend.
 *
 * <p>This exercises the SDK rather than our code, deliberately: the fact is load-bearing <em>for
 * us</em> — it is why a region that does not serve the model answers 404 rather than falling back,
 * and why {@code AnthropicConnectionCheck} treats a missing location as fatal — and it would
 * otherwise be a claim in a comment with nothing checking it.
 */
class VertexRequestShapeTest {

  private static VertexBackend backend(final String region) {
    return VertexBackend.builder()
        // Never used: nothing here signs a request, and building a backend reaches nothing.
        .googleCredentials(GoogleCredentials.newBuilder().build())
        .project("a-project")
        .region(region)
        .build();
  }

  @Test
  @DisplayName("the location is the hostname, which is why a wrong one cannot fall back")
  void theLocationIsTheHost() {
    assertThat(backend("us-east5").baseUrl())
        .isEqualTo("https://us-east5-aiplatform.googleapis.com");
    // The three that are not a plain region prefix, and the reason `location` is not free text a
    // deployment can guess at.
    assertThat(backend("global").baseUrl()).isEqualTo("https://aiplatform.googleapis.com");
    assertThat(backend("us").baseUrl()).isEqualTo("https://aiplatform.us.rep.googleapis.com");
    assertThat(backend("eu").baseUrl()).isEqualTo("https://aiplatform.eu.rep.googleapis.com");
  }
}
