package me.kezhenxu94.springagent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.RateLimitException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What reaches the log when an endpoint refuses, and what deliberately does not.
 *
 * <p>By the time a failure reaches {@code SpringAgent} an advisor has rewrapped it, so the status
 * and the reason are gone from the stack trace. The rule these tests pin is the one every {@code
 * ProviderRejection} follows: answer about the SDK's own service exception and nothing else, so
 * that a socket closing or a bug in this module is logged as itself rather than dressed up as
 * something the endpoint said.
 */
class AnthropicProviderRejectionTest {

  private final AnthropicProviderRejection rejection = new AnthropicProviderRejection();

  @Test
  @DisplayName("a rejection is reported with its status, type, request id and body")
  void aRejectionIsDescribed() {
    final var described =
        rejection.describe(
            BadRequestException.builder()
                .headers(Headers.builder().put("request-id", "req_123").build())
                .body(JsonValue.from(Map.of("error", Map.of("message", "bad model"))))
                .build());

    assertThat(described).isPresent();
    assertThat(described.get()).contains("status=400").contains("req_123").contains("bad model");
  }

  @Test
  @DisplayName("every status-carrying subclass is covered, not only the one that was tested")
  void theWholeFamilyIsCovered() {
    // Matching on the abstract base rather than on a list of subclasses is what makes this true,
    // and is why a status the SDK adds later needs no change here.
    assertThat(
            rejection.describe(
                RateLimitException.builder()
                    .headers(Headers.builder().build())
                    .body(JsonValue.from(Map.of()))
                    .build()))
        .isPresent();
  }

  @Test
  @DisplayName("a transport failure is not a rejection")
  void aTransportFailureFallsThrough() {
    // The endpoint said nothing, so there is nothing to report about what it said. Core's own
    // logging is the right place for this one.
    assertThat(rejection.describe(new AnthropicIoException("connection reset", null))).isEmpty();
    assertThat(rejection.describe(new IllegalStateException("a bug in this module"))).isEmpty();
  }
}
