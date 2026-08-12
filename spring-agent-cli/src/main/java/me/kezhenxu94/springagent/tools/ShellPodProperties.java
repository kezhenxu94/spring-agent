package me.kezhenxu94.springagent.tools;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.tools.shell-pod")
public record ShellPodProperties(
    Boolean enabled,
    String namespace,
    String image,
    List<String> imagePullSecrets,
    Duration idleTimeout,
    Duration hardDeadline,
    Duration startupTimeout,
    Integer maxOutputBytes,
    Long defaultTimeoutMs,
    Long maxTimeoutMs,
    Storage storage,
    Resources resources,
    Credentials credentials) {

  public ShellPodProperties {
    if (enabled == null) enabled = Boolean.TRUE;
    if (enabled && (image == null || image.isBlank())) {
      throw new IllegalArgumentException(
          "app.ai.tools.shell-pod.image must be set when app.ai.tools.shell-pod.enabled=true");
    }
    if (imagePullSecrets == null) {
      imagePullSecrets = List.of();
    } else {
      imagePullSecrets =
          imagePullSecrets.stream()
              .filter(s -> s != null && !s.isBlank())
              .map(String::trim)
              .toList();
    }
    if (idleTimeout == null) idleTimeout = Duration.ofMinutes(30);
    if (hardDeadline == null) hardDeadline = Duration.ofHours(4);
    if (startupTimeout == null) startupTimeout = Duration.ofSeconds(60);
    if (maxOutputBytes == null) maxOutputBytes = 30_000;
    if (defaultTimeoutMs == null) defaultTimeoutMs = 120_000L;
    if (maxTimeoutMs == null) maxTimeoutMs = 600_000L;
    if (storage == null) storage = new Storage(null, null);
    if (resources == null) resources = new Resources(null, null, null, null);
    if (credentials == null) credentials = new Credentials(null);
  }

  public record Storage(String pvcName, String pvcMountPath) {}

  public record Credentials(String mountPath) {
    public String mountPathOrDefault() {
      return mountPath == null || mountPath.isBlank() ? "/run/secrets/credentials" : mountPath;
    }
  }

  public record Resources(
      String cpuRequest, String memoryRequest, String cpuLimit, String memoryLimit) {
    public String cpuRequestOrDefault() {
      return cpuRequest == null ? "100m" : cpuRequest;
    }

    public String memoryRequestOrDefault() {
      return memoryRequest == null ? "256Mi" : memoryRequest;
    }

    public String cpuLimitOrDefault() {
      return cpuLimit == null ? "500m" : cpuLimit;
    }

    public String memoryLimitOrDefault() {
      return memoryLimit == null ? "1Gi" : memoryLimit;
    }
  }
}
