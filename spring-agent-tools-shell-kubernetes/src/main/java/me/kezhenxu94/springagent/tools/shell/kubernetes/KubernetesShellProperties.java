package me.kezhenxu94.springagent.tools.shell.kubernetes;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The sandbox Pod this module runs commands in.
 *
 * <p>Bound only when {@code app.ai.tools.shell.type=kubernetes} selects this module, which is why
 * there is no {@code enabled} flag and why {@code image} is required outright.
 */
@ConfigurationProperties(prefix = "app.ai.tools.shell.kubernetes")
public record KubernetesShellProperties(
    String namespace,
    String image,
    String workingDir,
    List<String> imagePullSecrets,
    Duration idleTimeout,
    Duration hardDeadline,
    Duration startupTimeout,
    Long defaultTimeoutMs,
    Long maxTimeoutMs,
    Long fsGroup,
    Storage storage,
    Resources resources,
    Credentials credentials) {

  public KubernetesShellProperties {
    if (image == null || image.isBlank()) {
      throw new IllegalArgumentException(
          "app.ai.tools.shell.kubernetes.image must be set when"
              + " app.ai.tools.shell.type=kubernetes");
    }
    if (workingDir == null || workingDir.isBlank()) {
      throw new IllegalArgumentException(
          "app.ai.tools.shell.kubernetes.working-dir must be set when"
              + " app.ai.tools.shell.type=kubernetes");
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
    if (defaultTimeoutMs == null) defaultTimeoutMs = 120_000L;
    if (maxTimeoutMs == null) maxTimeoutMs = 600_000L;
    if (storage == null) storage = new Storage(null);
    if (storage.mounts().isEmpty()) {
      throw new IllegalArgumentException(
          "app.ai.tools.shell.kubernetes.storage.mounts must have at least one entry when"
              + " app.ai.tools.shell.type=kubernetes");
    }
    if (resources == null) resources = new Resources(null, null, null, null);
    if (credentials == null) credentials = new Credentials(null);
  }

  /**
   * PVCs to mount into the shell sandbox Pod, all mounted the same way regardless of order - bound
   * as an indexed list, e.g. {@code storage.mounts[0].pvc-name} / {@code ..._MOUNTS_0_PVC_NAME} as
   * an env var. Every entry gets a per-user {@code subPath}, so one shared PVC serves every user's
   * Pod without their files colliding. {@link KubernetesShellProperties#workingDir} picks which
   * mount's path the shell starts in - it isn't inferred from list position.
   */
  public record Storage(List<Mount> mounts) {
    public Storage {
      mounts = mounts == null ? List.of() : mounts.stream().filter(m -> m != null).toList();
    }

    /**
     * @param pvcName the PVC to mount
     * @param mountPath absolute container path to mount at. Defaults to {@code /<pvcName>} when
     *     omitted.
     * @param subPathPrefix optional path segment prepended to the per-user {@code subPath}, for
     *     PVCs shared with other apps/purposes that need their own namespacing (e.g. an OSS bucket
     *     also used for file uploads). Defaults to none.
     */
    public record Mount(String pvcName, String mountPath, String subPathPrefix) {
      public Mount {
        if (pvcName == null || pvcName.isBlank()) {
          throw new IllegalArgumentException("pvcName must be set for every storage mount");
        }
        if (mountPath == null || mountPath.isBlank()) mountPath = "/" + pvcName;
        if (subPathPrefix == null) subPathPrefix = "";
      }

      public String subPath(final String userId) {
        return subPathPrefix.isBlank() ? userId : Path.of(subPathPrefix, userId).toString();
      }
    }
  }

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
      return cpuLimit == null ? "1000m" : cpuLimit;
    }

    public String memoryLimitOrDefault() {
      return memoryLimit == null ? "1Gi" : memoryLimit;
    }
  }
}
