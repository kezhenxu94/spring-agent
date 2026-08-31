package me.kezhenxu94.springagent.tools.shell.kubernetes;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
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
    if (credentials == null) credentials = new Credentials(null, null);
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

  /**
   * @param mountPath where the user's own credentials Secret is mounted as files.
   * @param shared label selectors picking up Secrets an operator has pre-provisioned for a group, a
   *     tenant or a named user. Ordered weakest-first: every one of them is applied before the
   *     user's own Secret, so a key the user set for themselves always wins.
   */
  public record Credentials(String mountPath, List<SharedSecretSelector> shared) {
    public Credentials {
      shared = shared == null ? List.of() : shared.stream().filter(s -> s != null).toList();
    }

    public String mountPathOrDefault() {
      return mountPath == null || mountPath.isBlank() ? "/run/secrets/credentials" : mountPath;
    }

    /**
     * One label selector over the Secrets in the sandbox namespace. Every Secret it matches is
     * added to the sandbox Pod as an {@code envFrom} source.
     *
     * <p>Values may carry the placeholders <code>{userId}</code>, <code>{groupId}</code> and <code>
     * {tenantId}</code>, which is what lets a single mechanism express "shared with this tenant",
     * "shared with this group" and "shared with this one user" without three code paths.
     *
     * <p>The selector is the whole access rule, so it must never key on a label an untrusted party
     * can set: any Secret in the namespace that carries matching labels becomes readable by the
     * sandbox this resolves for.
     *
     * <p><b>Label keys have to be bracketed in configuration</b> — {@code "[springagent.io/name]":
     * value}. Bound as a map key, an unbracketed name is canonicalised to {@code [a-z0-9-]}: split
     * on its dots, every other character dropped, the pieces rejoined with dots. {@code
     * springagent.io/shell-shared-user-id} therefore arrives as {@code
     * springagent.ioshell-shared-user-id}, which is still a legal label key, so the API server
     * answers with zero Secrets and the credential silently never reaches a sandbox.
     */
    public record SharedSecretSelector(Map<String, String> matchLabels) {
      /**
       * A Kubernetes label value: at most 63 characters, alphanumeric at both ends, dashes, dots
       * and underscores between. A user, group or tenant id is not required to look like this — an
       * id that does not is skipped rather than sent, because the API server answers an illegal
       * selector with a 400 that would take the sandbox down for everyone in that scope.
       */
      private static final Pattern LABEL_VALUE =
          Pattern.compile("[A-Za-z0-9]([A-Za-z0-9._-]{0,61}[A-Za-z0-9])?");

      public SharedSecretSelector {
        if (matchLabels == null || matchLabels.isEmpty()) {
          throw new IllegalArgumentException(
              "match-labels must have at least one entry for every"
                  + " app.ai.tools.shell.kubernetes.credentials.shared selector: an empty selector"
                  + " matches every Secret in the namespace");
        }
        matchLabels.forEach(
            (key, value) -> {
              if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("a match-labels key must not be blank");
              }
              if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                    "match-labels value for " + key + " must be set");
              }
            });
        matchLabels = Map.copyOf(matchLabels);
      }

      /**
       * The labels to select on for this scope, or empty when this selector does not apply to it.
       *
       * <p>A placeholder that resolves to blank drops the whole selector rather than matching on an
       * empty value: {@code springagent.io/group-id: ""} would match every Secret that stores a
       * blank there, which is every other group's.
       */
      public Optional<Map<String, String>> resolve(
          final String userId, final String groupId, final String tenantId) {
        final var resolved = new LinkedHashMap<String, String>(matchLabels.size());
        for (final var entry : matchLabels.entrySet()) {
          final var value = entry.getValue();
          final var substituted =
              value
                  .replace("{userId}", nullToEmpty(userId))
                  .replace("{groupId}", nullToEmpty(groupId))
                  .replace("{tenantId}", nullToEmpty(tenantId));
          if (substituted.isBlank() || !LABEL_VALUE.matcher(substituted).matches()) {
            return Optional.empty();
          }
          resolved.put(entry.getKey(), substituted);
        }
        return Optional.of(resolved);
      }

      private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
      }
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
