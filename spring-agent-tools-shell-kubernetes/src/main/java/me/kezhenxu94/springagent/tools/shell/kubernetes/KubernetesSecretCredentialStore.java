package me.kezhenxu94.springagent.tools.shell.kubernetes;

import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.credentials.ShellCredentialStore;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A per-user Kubernetes Secret, mounted into that user's sandbox Pod.
 *
 * <p>The storage half of what used to be {@code CredentialTools}; the tools themselves now live in
 * {@code spring-agent-core} and are shared with the Docker shell.
 *
 * <p>The Pod refers to this Secret by name through {@code envFrom} and a volume, so on this backend
 * a credential's value normally never passes through this application at all — {@link #resolve}
 * exists for the contract and is not on the path that fills a sandbox.
 */
@Slf4j
@RequiredArgsConstructor
public class KubernetesSecretCredentialStore implements ShellCredentialStore {

  private static final int MAX_TOTAL_BYTES = 900 * 1024;
  private static final int MAX_ENTRIES = 100;
  private static final String UPDATED_ANNOTATION = "springagent.io/cred-updated";
  private static final String SECRET_LABEL = "springagent.io/shell-credentials";

  private final KubernetesClient kubernetesClient;
  private final UserPodManager userPodManager;
  private final JsonMapper objectMapper = new JsonMapper();

  @Override
  public void put(final String userId, final String name, final String value) {
    final var ns = userPodManager.namespace();
    final var secretName = userPodManager.credentialsSecretName(userId);
    final var encoded = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    final var nowIso = Instant.now().toString();

    final var existing = kubernetesClient.secrets().inNamespace(ns).withName(secretName).get();
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
      return;
    }

    final var data =
        new LinkedHashMap<>(
            existing.getData() == null ? Map.<String, String>of() : existing.getData());
    assertCapacity(data, name, encoded);
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
    log.info("Updated shell credentials Secret {} for user {} (key={})", secretName, userId, name);
  }

  @Override
  public List<Entry> list(final String userId) {
    final var secret = secretFor(userId);
    if (secret == null || secret.getData() == null || secret.getData().isEmpty()) {
      return List.of();
    }
    final var updated =
        readUpdatedMap(
            secret.getMetadata().getAnnotations() == null
                ? null
                : secret.getMetadata().getAnnotations().get(UPDATED_ANNOTATION));
    return secret.getData().keySet().stream()
        .map(name -> new Entry(name, parseInstant(updated.get(name))))
        .toList();
  }

  @Override
  public boolean delete(final String userId, final String name) {
    final var ns = userPodManager.namespace();
    final var secretName = userPodManager.credentialsSecretName(userId);
    final var existing = kubernetesClient.secrets().inNamespace(ns).withName(secretName).get();
    if (existing == null || existing.getData() == null || !existing.getData().containsKey(name)) {
      return false;
    }
    final var data = new LinkedHashMap<>(existing.getData());
    data.remove(name);

    if (data.isEmpty()) {
      kubernetesClient.secrets().inNamespace(ns).withName(secretName).delete();
      log.info("Deleted empty shell credentials Secret {} for user {}", secretName, userId);
      return true;
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
    return true;
  }

  @Override
  public Map<String, String> resolve(final String userId) {
    final var secret = secretFor(userId);
    if (secret == null || secret.getData() == null) {
      return Map.of();
    }
    final var resolved = new LinkedHashMap<String, String>();
    secret
        .getData()
        .forEach(
            (name, encoded) ->
                resolved.put(
                    name, new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)));
    return resolved;
  }

  private io.fabric8.kubernetes.api.model.Secret secretFor(final String userId) {
    return kubernetesClient
        .secrets()
        .inNamespace(userPodManager.namespace())
        .withName(userPodManager.credentialsSecretName(userId))
        .get();
  }

  /** A Secret is capped at 1MiB by the API server; refuse before it does, with a clearer reason. */
  private void assertCapacity(
      final Map<String, String> data, final String newName, final String newEncoded) {
    final var newKeyCount = data.containsKey(newName) ? data.size() : data.size() + 1;
    if (newKeyCount > MAX_ENTRIES) {
      throw new CredentialStoreException("too many credentials (max " + MAX_ENTRIES + ")");
    }
    var totalBytes = 0;
    for (final var entry : data.entrySet()) {
      if (entry.getKey().equals(newName)) continue;
      totalBytes += approxEntrySize(entry.getKey(), entry.getValue());
    }
    totalBytes += approxEntrySize(newName, newEncoded);
    if (totalBytes > MAX_TOTAL_BYTES) {
      throw new CredentialStoreException(
          "secret would exceed " + MAX_TOTAL_BYTES + " bytes (currently " + totalBytes + ")");
    }
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

  private static Instant parseInstant(final String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(raw);
    } catch (final RuntimeException e) {
      return null;
    }
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
}
