package me.kezhenxu94.springagent.core.tools.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.common.McpTransportContext;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Covers the two halves of the per-call header path without a live server: what the {@code
 * transportContextProvider} supplier resolves, and what the transport's request customizer then
 * puts on the request. Between them sits only the SDK's own Reactor context.
 */
class McpClientFactoryHeadersTest {

  private static final McpServerConfig SERVER =
      McpServerConfig.builder()
          .id("1")
          .ownerId("ou_1")
          .name("github-mcp")
          .transport(McpServerConfig.Transport.STREAMABLE_HTTP)
          .url("https://example.invalid/mcp")
          .headers(new LinkedHashMap<>(Map.of("X-Static", "from-config")))
          .enabled(true)
          .build();

  private static McpClientFactory factoryWith(final McpHeaderContributor... contributors) {
    return new McpClientFactory(new McpProperties(List.of()), List.of(contributors));
  }

  /** Runs a customizer over a fresh request builder and reports the headers it set. */
  private static Map<String, List<String>> headersAfter(
      final McpClientFactory factory, final McpTransportContext context) {
    final var uri = URI.create(SERVER.url());
    final var builder = HttpRequest.newBuilder(uri);
    factory.headerCustomizer(SERVER).customize(builder, "POST", uri, "{}", context);
    return builder.build().headers().map();
  }

  @Test
  @DisplayName("a contributor's header reaches the outgoing request")
  void contributedHeaderReachesTheRequest() {
    final var factory =
        factoryWith(
            (server, toolContext) ->
                Map.of("X-User", ToolContexts.require(toolContext, ToolContexts.USER_ID)));

    final var resolved =
        factory.dynamicHeaders(
            SERVER, new ToolContext(Map.of(ToolContexts.KEY_USER_ID, "ou_caller")));
    assertThat(resolved).containsEntry("X-User", "ou_caller");

    assertThat(headersAfter(factory, McpDynamicHeaders.carrying(resolved)))
        .containsEntry("x-user", List.of("ou_caller"))
        .containsEntry("x-static", List.of("from-config"));
  }

  @Test
  @DisplayName("a contributed header replaces the config's header of the same name")
  void contributedHeaderReplacesTheStaticOne() {
    final var factory = factoryWith((server, toolContext) -> Map.of("X-Static", "from-caller"));

    assertThat(
            headersAfter(
                factory,
                McpDynamicHeaders.carrying(
                    factory.dynamicHeaders(SERVER, new ToolContext(Map.of())))))
        .as("appending rather than replacing would send both values")
        .containsEntry("x-static", List.of("from-caller"));
  }

  @Test
  @DisplayName("a contributor that throws costs its own headers, not the call")
  void aThrowingContributorIsSkipped() {
    final var factory =
        factoryWith(
            (server, toolContext) -> {
              throw new IllegalStateException("no token today");
            },
            (server, toolContext) -> Map.of("X-Other", "still-here"));

    assertThat(factory.dynamicHeaders(SERVER, new ToolContext(Map.of())))
        .containsExactly(Map.entry("X-Other", "still-here"));
  }

  @Test
  @DisplayName("a request the transport makes on its own gets the static headers only")
  void requestsWithoutACallBehindThemGetStaticHeadersOnly() {
    final var factory = factoryWith((server, toolContext) -> Map.of("X-User", "ou_caller"));

    // Opening the SSE stream, resuming it and deleting the session are the transport's own
    // requests: no McpSyncClient call is behind them, so they carry no context of ours.
    for (final var context : new McpTransportContext[] {null, McpTransportContext.EMPTY}) {
      assertThat(headersAfter(factory, context))
          .containsEntry("x-static", List.of("from-config"))
          .doesNotContainKey("x-user");
    }
  }

  @Test
  @DisplayName("with no contributors registered nothing is resolved")
  void noContributorsResolvesNothing() {
    assertThat(factoryWith().dynamicHeaders(SERVER, new ToolContext(Map.of()))).isEmpty();
  }
}
