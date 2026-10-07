package me.kezhenxu94.springagent.core.tools.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.util.List;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class McpClientFactoryTest {

  private final McpClientFactory factory =
      new McpClientFactory(new McpProperties(List.of()), List.of());

  @Test
  @DisplayName("rejects non-https URLs")
  void rejectsNonHttps() {
    assertThatThrownBy(() -> factory.validateRemoteUrl("http://8.8.8.8/"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects URLs without a host")
  void rejectsNoHost() {
    assertThatThrownBy(() -> factory.validateRemoteUrl("https:///mcp"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("blocks loopback / private / link-local / IPv6-ULA / multicast hosts")
  void blocksPrivateRanges() {
    final var blocked =
        List.of(
            "https://127.0.0.1/",
            "https://[::1]/",
            "https://10.0.0.1/",
            "https://192.168.1.1/",
            "https://172.16.0.1/",
            "https://169.254.169.254/", // cloud metadata endpoint
            "https://[fe80::1]/",
            "https://[fd00::1]/", // IPv6 unique local address (fc00::/7)
            "https://224.0.0.1/");
    for (final var url : blocked) {
      assertThatThrownBy(() -> factory.validateRemoteUrl(url))
          .as("should block %s", url)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("allows public https hosts")
  void allowsPublic() {
    assertThatCode(() -> factory.validateRemoteUrl("https://8.8.8.8/mcp"))
        .doesNotThrowAnyException();
    assertThatCode(() -> factory.validateRemoteUrl("https://[2001:4860:4860::8888]/"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("trusted hosts bypass the https-only and private-address checks")
  void trustedHostsBypassGuard() {
    final var trustedFactory =
        new McpClientFactory(new McpProperties(List.of("monitoring-mcp.monitoring")), List.of());

    assertThatCode(() -> trustedFactory.validateRemoteUrl("http://monitoring-mcp.monitoring/mcp"))
        .doesNotThrowAnyException();
    assertThatCode(() -> trustedFactory.validateRemoteUrl("http://MONITORING-MCP.monitoring/mcp"))
        .as("host matching is case-insensitive")
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("non-trusted hosts are still blocked even when a trusted host is configured")
  void nonTrustedHostsStillBlocked() {
    final var trustedFactory =
        new McpClientFactory(new McpProperties(List.of("monitoring-mcp.monitoring")), List.of());

    assertThatThrownBy(
            () -> trustedFactory.validateRemoteUrl("http://some-other-internal-svc.default/mcp"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("trusted hosts still require the http or https scheme")
  void trustedHostsStillRequireHttpOrHttpsScheme() {
    final var trustedFactory =
        new McpClientFactory(new McpProperties(List.of("monitoring-mcp.monitoring")), List.of());

    assertThatThrownBy(
            () -> trustedFactory.validateRemoteUrl("file://monitoring-mcp.monitoring/mcp"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("hashPrefix is deterministic, ASCII-safe and always the same length")
  void hashPrefixIsDeterministicAndSafe() {
    final var prefix = McpClientFactory.hashPrefix("github-mcp(🚀)");
    assertThat(prefix).matches("^[a-z0-9_]+$");
    assertThat(prefix).isEqualTo(McpClientFactory.hashPrefix("github-mcp(🚀)"));
  }

  @Test
  @DisplayName("hashPrefix distinguishes names that only differ by non-ASCII text")
  void hashPrefixDistinguishesUnicodeOnlyDifferences() {
    final var green = McpClientFactory.hashPrefix("github-mcp(🟢)");
    final var beaker = McpClientFactory.hashPrefix("github-mcp(🧪)");
    assertThat(green).isNotEqualTo(beaker);
  }

  @Test
  @DisplayName("hashHex returns exactly the requested number of hex characters")
  void hashHexRespectsRequestedLength() {
    assertThat(McpClientFactory.hashHex("github-mcp", 16)).hasSize(16).matches("^[0-9a-f]+$");
    assertThat(McpClientFactory.hashHex("github-mcp", 8)).hasSize(8).matches("^[0-9a-f]+$");
  }

  @Test
  @DisplayName("every transport talks through one HTTP client, so a run leaks no selector thread")
  void transportsShareOneHttpClient() throws Exception {
    final var first = httpClientOf(factory.buildTransport(server("a")));
    final var second = httpClientOf(factory.buildTransport(server("b")));
    // A JDK HttpClient holds a selector thread until it is collected, and the transport never
    // closes the one it builds; one per server per run is what leaked them.
    assertThat(first).isSameAs(second);
  }

  private static HttpClient httpClientOf(final Object transport) throws Exception {
    final var field = transport.getClass().getDeclaredField("httpClient");
    field.setAccessible(true);
    return (HttpClient) field.get(transport);
  }

  private static McpServerConfig server(final String name) {
    return McpServerConfig.builder()
        .id(name)
        .ownerId("ou_1")
        .name(name)
        .transport(McpServerConfig.Transport.STREAMABLE_HTTP)
        .url("https://" + name + ".example.invalid/mcp")
        .enabled(true)
        .build();
  }
}
