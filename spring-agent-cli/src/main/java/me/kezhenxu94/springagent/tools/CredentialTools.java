package me.kezhenxu94.springagent.tools;

import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ai.tools.shell-pod", name = "enabled", havingValue = "true")
public class CredentialTools {

  private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
  private static final int MAX_VALUE_BYTES = 64 * 1024;
  private static final int MAX_TOTAL_BYTES = 900 * 1024;
  private static final int MAX_ENTRIES = 100;
  private static final String UPDATED_ANNOTATION = "springagent.io/cred-updated";
  private static final String SECRET_LABEL = "springagent.io/shell-credentials";

  private final KubernetesClient kubernetesClient;
  private final UserPodManager userPodManager;
  private final JsonMapper objectMapper = new JsonMapper();

  // @formatter:off
  @Tool(
      name = "SetCredential",
      description =
"""
- Stores a credential (token, API key, password) in the user's shell sandbox.
- The credential is exposed inside the shell pod as:
    * environment variable $NAME (requires RestartShellPod to take effect)
    * read-only file at /run/secrets/credentials/NAME (auto-refreshes)
- NAME must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$ to be a valid POSIX env-var name.
- Values are never echoed back; use ListCredentials to see which credentials are stored.
""")
  public String setCredential(
      @ToolParam(description = "Credential name (env-var-safe identifier)") final String name,
      @ToolParam(description = "Credential value") final String value,
      final ToolContext toolContext) {
    // @formatter:on

    final var userId = userIdFrom(toolContext);
    if (userId == null) {
      return "Error: credential store unavailable: no userId in tool context";
    }
    if (name == null || !NAME_PATTERN.matcher(name).matches()) {
      return "Error: invalid credential name. Must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$";
    }
    if (value == null) {
      return "Error: value must not be null";
    }
    final var valueBytes = value.getBytes(StandardCharsets.UTF_8);
    if (valueBytes.length > MAX_VALUE_BYTES) {
      return "Error: value too large ("
          + valueBytes.length
          + " bytes, max "
          + MAX_VALUE_BYTES
          + ")";
    }

    final var ns = userPodManager.namespace();
    final var secretName = userPodManager.credentialsSecretName(userId);

    try {
      final var existing = kubernetesClient.secrets().inNamespace(ns).withName(secretName).get();
      final var encoded = Base64.getEncoder().encodeToString(valueBytes);
      final var nowIso = Instant.now().toString();

      if (existing == null) {
        final var data = new LinkedHashMap<String, String>();
        data.put(name, encoded);
        final var updatedMap = new TreeMap<String, String>();
        updatedMap.put(name, nowIso);
        final var secret =
            new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .withLabels(secretLabels(userId))
                .withAnnotations(Map.of(UPDATED_ANNOTATION, writeUpdatedMap(updatedMap)))
                .endMetadata()
                .withType("Opaque")
                .withData(data)
                .build();
        kubernetesClient.secrets().inNamespace(ns).resource(secret).create();
        log.info("Created shell credentials Secret {} for user {}", secretName, userId);
      } else {
        final var data =
            new LinkedHashMap<>(
                existing.getData() == null ? Map.<String, String>of() : existing.getData());
        final var capacityError = assertCapacity(data, name, encoded);
        if (capacityError != null) {
          return capacityError;
        }
        data.put(name, encoded);
        existing.setData(data);

        final var annotations =
            new HashMap<>(
                existing.getMetadata().getAnnotations() == null
                    ? Map.<String, String>of()
                    : existing.getMetadata().getAnnotations());
        final var updatedMap = readUpdatedMap(annotations.get(UPDATED_ANNOTATION));
        updatedMap.put(name, nowIso);
        annotations.put(UPDATED_ANNOTATION, writeUpdatedMap(updatedMap));
        existing.getMetadata().setAnnotations(annotations);

        final var labels =
            new HashMap<>(
                existing.getMetadata().getLabels() == null
                    ? Map.<String, String>of()
                    : existing.getMetadata().getLabels());
        labels.putAll(secretLabels(userId));
        existing.getMetadata().setLabels(labels);

        existing.getMetadata().setManagedFields(null);
        kubernetesClient.secrets().inNamespace(ns).resource(existing).update();
        log.info(
            "Updated shell credentials Secret {} for user {} (key={})", secretName, userId, name);
      }
      return "Credential "
          + name
          + " stored. Run RestartShellPod to expose it as $"
          + name
          + " (the file at /run/secrets/credentials/"
          + name
          + " refreshes automatically).";
    } catch (final Exception e) {
      log.error("SetCredential failed user={} name={}", userId, name, e);
      return "Error storing credential: " + e.getMessage();
    }
  }

  // @formatter:off
  @Tool(
      name = "ListCredentials",
      description =
"""
- Lists the names of credentials the user has stored in the shell sandbox.
- Returns each credential's name and last-updated timestamp.
- Values are never returned.
""")
  public String listCredentials(final ToolContext toolContext) {
    // @formatter:on

    final var userId = userIdFrom(toolContext);
    if (userId == null) {
      return "Error: credential store unavailable: no userId in tool context";
    }
    final var ns = userPodManager.namespace();
    final var secretName = userPodManager.credentialsSecretName(userId);

    try {
      final var secret = kubernetesClient.secrets().inNamespace(ns).withName(secretName).get();
      if (secret == null || secret.getData() == null || secret.getData().isEmpty()) {
        return "No credentials stored.";
      }
      final var updated =
          readUpdatedMap(
              secret.getMetadata().getAnnotations() == null
                  ? null
                  : secret.getMetadata().getAnnotations().get(UPDATED_ANNOTATION));
      final var out = new StringBuilder();
      out.append("Credentials:\n");
      secret.getData().keySet().stream()
          .sorted(Comparator.naturalOrder())
          .forEach(
              key -> {
                final var ts = updated.getOrDefault(key, "unknown");
                out.append("- ").append(key).append("  (lastUpdated=").append(ts).append(")\n");
              });
      return out.toString().stripTrailing();
    } catch (final Exception e) {
      log.error("ListCredentials failed user={}", userId, e);
      return "Error listing credentials: " + e.getMessage();
    }
  }

  // @formatter:off
  @Tool(
      name = "DeleteCredential",
      description =
"""
- Removes a credential from the user's shell sandbox.
- If this was the last credential, the underlying secret is deleted.
- Idempotent: returns success even if the credential does not exist.
- Run RestartShellPod afterwards so the env var disappears from the running pod.
""")
  public String deleteCredential(
      @ToolParam(description = "Credential name to remove") final String name,
      final ToolContext toolContext) {
    // @formatter:on

    final var userId = userIdFrom(toolContext);
    if (userId == null) {
      return "Error: credential store unavailable: no userId in tool context";
    }
    if (name == null || !NAME_PATTERN.matcher(name).matches()) {
      return "Error: invalid credential name. Must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$";
    }
    final var ns = userPodManager.namespace();
    final var secretName = userPodManager.credentialsSecretName(userId);

    try {
      final var existing = kubernetesClient.secrets().inNamespace(ns).withName(secretName).get();
      if (existing == null || existing.getData() == null || !existing.getData().containsKey(name)) {
        return "Credential " + name + " not found (nothing to delete).";
      }
      final var data = new LinkedHashMap<>(existing.getData());
      data.remove(name);

      if (data.isEmpty()) {
        kubernetesClient.secrets().inNamespace(ns).withName(secretName).delete();
        log.info("Deleted empty shell credentials Secret {} for user {}", secretName, userId);
        return "Credential "
            + name
            + " removed. No other credentials remain; run RestartShellPod to drop $"
            + name
            + " from the pod.";
      }

      existing.setData(data);
      final var annotations =
          new HashMap<>(
              existing.getMetadata().getAnnotations() == null
                  ? Map.<String, String>of()
                  : existing.getMetadata().getAnnotations());
      final var updatedMap = readUpdatedMap(annotations.get(UPDATED_ANNOTATION));
      updatedMap.remove(name);
      annotations.put(UPDATED_ANNOTATION, writeUpdatedMap(updatedMap));
      existing.getMetadata().setAnnotations(annotations);

      existing.getMetadata().setManagedFields(null);
      kubernetesClient.secrets().inNamespace(ns).resource(existing).update();
      log.info("Removed credential {} from Secret {} for user {}", name, secretName, userId);
      return "Credential "
          + name
          + " removed. Run RestartShellPod to drop $"
          + name
          + " from the pod.";
    } catch (final Exception e) {
      log.error("DeleteCredential failed user={} name={}", userId, name, e);
      return "Error deleting credential: " + e.getMessage();
    }
  }

  private String assertCapacity(
      final Map<String, String> data, final String newName, final String newEncoded) {
    final var newKeyCount = data.containsKey(newName) ? data.size() : data.size() + 1;
    if (newKeyCount > MAX_ENTRIES) {
      return "Error: too many credentials (max " + MAX_ENTRIES + ")";
    }
    var totalBytes = 0;
    for (final var entry : data.entrySet()) {
      if (entry.getKey().equals(newName)) continue;
      totalBytes += approxEntrySize(entry.getKey(), entry.getValue());
    }
    totalBytes += approxEntrySize(newName, newEncoded);
    if (totalBytes > MAX_TOTAL_BYTES) {
      return "Error: secret would exceed "
          + MAX_TOTAL_BYTES
          + " bytes (currently "
          + totalBytes
          + ")";
    }
    return null;
  }

  private static int approxEntrySize(final String key, final String base64Value) {
    return key.getBytes(StandardCharsets.UTF_8).length
        + base64Value.getBytes(StandardCharsets.UTF_8).length;
  }

  private static Map<String, String> secretLabels(final String userId) {
    return Map.of(
        UserPodManager.LABEL_APP,
        "spring-agent-shell-credentials",
        SECRET_LABEL,
        "true",
        UserPodManager.LABEL_OWNER_USER_ID,
        userId);
  }

  private Map<String, String> readUpdatedMap(final String raw) {
    if (raw == null || raw.isBlank()) {
      return new TreeMap<>();
    }
    try {
      return new TreeMap<>(
          objectMapper.readValue(raw, new TypeReference<Map<String, String>>() {}));
    } catch (final JacksonException e) {
      log.warn("Failed to parse {} annotation; resetting", UPDATED_ANNOTATION, e);
      return new TreeMap<>();
    }
  }

  private String writeUpdatedMap(final Map<String, String> map) {
    try {
      return objectMapper.writeValueAsString(map);
    } catch (final JacksonException e) {
      throw new IllegalStateException("Failed to serialize updated map", e);
    }
  }

  private static String userIdFrom(final ToolContext context) {
    if (context == null || context.getContext() == null) return null;
    final var v = context.getContext().get("userId");
    if (v == null) return null;
    final var s = v.toString();
    return s.isBlank() ? null : s;
  }
}
