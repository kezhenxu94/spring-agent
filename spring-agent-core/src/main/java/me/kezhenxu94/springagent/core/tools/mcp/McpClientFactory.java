package me.kezhenxu94.springagent.core.tools.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import org.springframework.stereotype.Component;

/**
 * Builds and initializes an {@link McpSyncClient} for a user-registered remote MCP server.
 *
 * <p>Only remote streamable HTTP servers are supported — stdio servers would launch local
 * subprocesses on the bot pod, and SSE was dropped after the upstream MCP SDK deprecated it. A
 * best-effort SSRF guard rejects non-https URLs and hosts that resolve to private / loopback /
 * link-local addresses before any connection is attempted, unless the host is explicitly
 * allowlisted via {@code app.ai.tools.mcp.trusted-hosts} (e.g. known in-cluster services such as
 * github-mcp).
 */
@Slf4j
@Component
public class McpClientFactory {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
  private static final int HASH_PREFIX_LENGTH = 16;

  private final Set<String> trustedHosts;

  public McpClientFactory(final McpProperties properties) {
    this.trustedHosts =
        properties.trustedHosts().stream()
            .map(h -> h.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Validates the URL, opens the transport and performs the MCP handshake. Throws on any failure;
   * the caller decides whether to skip the server (per-request assembly) or surface the error
   * (registration).
   */
  public McpSyncClient createAndInitialize(final McpServerConfig config) {
    validateRemoteUrl(config.url());
    final var transport = buildTransport(config);
    final var version =
        config.version() == null || config.version().isBlank()
            ? McpServerConfig.DEFAULT_VERSION
            : config.version();
    final var title =
        config.title() == null || config.title().isBlank() ? config.name() : config.title();
    final var clientInfo =
        McpSchema.Implementation.builder(hashPrefix(config.name()), version)
            .title(title)
            .description(config.description())
            .websiteUrl(config.websiteUrl())
            .build();
    final var client =
        McpClient.sync(transport).clientInfo(clientInfo).requestTimeout(REQUEST_TIMEOUT).build();
    client.initialize();
    return client;
  }

  /**
   * Derives an ASCII, tool-name-safe prefix from a user-chosen MCP server name via a 64-bit MD5
   * digest, so different names are, in practice, exceedingly unlikely to map to the same prefix —
   * including names that only differ by non-ASCII text, e.g. "github-mcp(🟢)" vs "github-mcp(🧪)",
   * which a character-stripping slug would otherwise collapse to the same prefix. See {@link
   * ServerNameToolPrefixGenerator}.
   */
  static String hashPrefix(final String name) {
    return "mcp_" + hashHex(name, HASH_PREFIX_LENGTH);
  }

  /** Hex-encodes the first {@code hexLen} hex characters of the input's MD5 digest. */
  static String hashHex(final String input, final int hexLen) {
    try {
      final var digest =
          MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, (hexLen + 1) / 2).substring(0, hexLen);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 unavailable", e);
    }
  }

  private McpClientTransport buildTransport(final McpServerConfig config) {
    final var headers = config.headers();
    final var clientBuilder = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT);
    final McpSyncHttpClientRequestCustomizer headerCustomizer =
        (requestBuilder, method, uri, body, context) -> {
          if (headers != null) {
            headers.forEach(requestBuilder::header);
          }
        };
    return HttpClientStreamableHttpTransport.builder(config.url())
        .clientBuilder(clientBuilder)
        .httpRequestCustomizer(headerCustomizer)
        .build();
  }

  /**
   * Best-effort SSRF guard: require https and reject hosts resolving to private / loopback /
   * link-local ranges (covers cloud metadata endpoints such as 169.254.169.254 and cluster-internal
   * services). There is an inherent TOCTOU gap versus the later connection; acceptable for v1.
   *
   * <p>Hosts listed in {@code app.ai.tools.mcp.trusted-hosts} skip both the https-only and
   * private-address checks — this is a narrow, operator-controlled allowlist (e.g. github-mcp at a
   * known in-cluster Service address), not a general relaxation for user-registered servers.
   */
  void validateRemoteUrl(final String url) {
    final URI uri;
    try {
      uri = URI.create(url);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid MCP server URL: " + url);
    }
    final var scheme = uri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
      throw new IllegalArgumentException("Only http(s):// MCP server URLs are allowed: " + url);
    }
    final var host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("MCP server URL has no host: " + url);
    }
    if (trustedHosts.contains(host.toLowerCase(Locale.ROOT))) {
      return;
    }
    if (!"https".equalsIgnoreCase(scheme)) {
      throw new IllegalArgumentException("Only https:// MCP server URLs are allowed: " + url);
    }
    try {
      for (final var addr : InetAddress.getAllByName(host)) {
        if (isBlockedAddress(addr)) {
          throw new IllegalArgumentException(
              "MCP server host resolves to a disallowed private/loopback address: " + host);
        }
      }
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("Cannot resolve MCP server host: " + host);
    }
  }

  private boolean isBlockedAddress(final InetAddress addr) {
    return addr.isAnyLocalAddress()
        || addr.isLoopbackAddress()
        || addr.isLinkLocalAddress()
        || addr.isSiteLocalAddress()
        || addr.isMulticastAddress()
        || isIpv6UniqueLocal(addr);
  }

  /**
   * IPv6 Unique Local Addresses (fc00::/7) are not covered by {@link
   * InetAddress#isSiteLocalAddress()}, which for IPv6 only matches the deprecated fec0::/10 range —
   * so guard them explicitly.
   */
  private boolean isIpv6UniqueLocal(final InetAddress addr) {
    return addr instanceof Inet6Address && (addr.getAddress()[0] & 0xfe) == 0xfc;
  }
}
