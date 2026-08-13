package me.kezhenxu94.springagent.tools.shell.docker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The sandbox container this module runs commands in.
 *
 * <p>Bound only when {@code app.ai.tools.shell.type=docker} selects this module, which is why there
 * is no {@code enabled} flag and why {@code image} is required outright.
 *
 * <p>The Kubernetes counterpart carries both resource requests and limits. Docker has no notion of
 * a request — a container gets what the host has until it hits its limit — so only the limits
 * survive, under the same names.
 */
@ConfigurationProperties(prefix = "app.ai.tools.shell.docker")
public record DockerShellProperties(
    String image,
    String network,
    Duration idleTimeout,
    Duration hardDeadline,
    Duration startupTimeout,
    Integer maxOutputBytes,
    Long defaultTimeoutMs,
    Long maxTimeoutMs,
    Resources resources,
    Credentials credentials) {

  public DockerShellProperties {
    if (image == null || image.isBlank()) {
      throw new IllegalArgumentException(
          "app.ai.tools.shell.docker.image must be set when app.ai.tools.shell.type=docker");
    }
    if (idleTimeout == null) idleTimeout = Duration.ofMinutes(30);
    if (hardDeadline == null) hardDeadline = Duration.ofHours(4);
    if (startupTimeout == null) startupTimeout = Duration.ofSeconds(60);
    if (maxOutputBytes == null) maxOutputBytes = 30_000;
    if (defaultTimeoutMs == null) defaultTimeoutMs = 120_000L;
    if (maxTimeoutMs == null) maxTimeoutMs = 600_000L;
    if (resources == null) resources = new Resources(null, null);
    if (credentials == null) credentials = new Credentials(null, null);
    // Required up front rather than on the first SetCredential, so that a deployment learns it is
    // missing at startup instead of when a user has already handed the agent a token.
    if (credentials.encryptionKey() == null || credentials.encryptionKey().isBlank()) {
      throw new IllegalArgumentException(
          "app.ai.tools.shell.docker.credentials.encryption-key must be set when"
              + " app.ai.tools.shell.type=docker; generate one with"
              + " `head -c 32 /dev/urandom | base64`");
    }
  }

  public record Credentials(String mountPath, String encryptionKey) {
    public String mountPathOrDefault() {
      return mountPath == null || mountPath.isBlank() ? "/run/secrets/credentials" : mountPath;
    }
  }

  /**
   * CPU and memory ceilings for a sandbox container.
   *
   * @param cpuLimit Kubernetes-style ({@code 1000m}) or Docker-style ({@code 1.5}) CPU count.
   * @param memoryLimit a Kubernetes quantity: {@code 1Gi}, {@code 512Mi}, {@code 1G}, or plain
   *     bytes.
   */
  public record Resources(String cpuLimit, String memoryLimit) {

    private static final long KI = 1024L;
    private static final long MI = KI * 1024L;
    private static final long GI = MI * 1024L;

    public String cpuLimitOrDefault() {
      return cpuLimit == null || cpuLimit.isBlank() ? "1000m" : cpuLimit.trim();
    }

    public String memoryLimitOrDefault() {
      return memoryLimit == null || memoryLimit.isBlank() ? "1Gi" : memoryLimit.trim();
    }

    /** The CPU limit as Docker's {@code NanoCPUs}: one whole CPU is 1e9. */
    public long nanoCpus() {
      final var value = cpuLimitOrDefault();
      final var cpus =
          value.endsWith("m")
              ? Double.parseDouble(value.substring(0, value.length() - 1)) / 1000d
              : Double.parseDouble(value);
      if (cpus <= 0) {
        throw new IllegalArgumentException(
            "app.ai.tools.shell.docker.resources.cpu-limit must be positive, was " + value);
      }
      return Math.round(cpus * 1_000_000_000d);
    }

    /** The memory limit in bytes, as Docker's {@code HostConfig.Memory} wants it. */
    public long memoryBytes() {
      final var value = memoryLimitOrDefault();
      final var bytes =
          switch (suffixOf(value)) {
            case "Gi" -> number(value, 2) * GI;
            case "Mi" -> number(value, 2) * MI;
            case "Ki" -> number(value, 2) * KI;
            case "G" -> number(value, 1) * 1_000_000_000L;
            case "M" -> number(value, 1) * 1_000_000L;
            case "K" -> number(value, 1) * 1_000L;
            default -> Long.parseLong(value);
          };
      if (bytes <= 0) {
        throw new IllegalArgumentException(
            "app.ai.tools.shell.docker.resources.memory-limit must be positive, was " + value);
      }
      return bytes;
    }

    private static String suffixOf(final String value) {
      if (value.length() > 2 && value.endsWith("i")) {
        return value.substring(value.length() - 2);
      }
      if (value.length() > 1 && !Character.isDigit(value.charAt(value.length() - 1))) {
        return value.substring(value.length() - 1);
      }
      return "";
    }

    private static long number(final String value, final int suffixLength) {
      return Long.parseLong(value.substring(0, value.length() - suffixLength));
    }
  }
}
