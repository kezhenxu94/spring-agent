package me.kezhenxu94.springagent.tools.shell.kubernetes;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.Admins;

@Slf4j
@RequiredArgsConstructor
public class UserPodManager {

  public static final String LABEL_APP = "app";
  public static final String LABEL_APP_VALUE = "spring-agent-shell";
  public static final String LABEL_SHELL_POD = "springagent.io/shell-pod";
  public static final String LABEL_OWNER_USER_ID = "springagent.io/owner-user-id";
  public static final String LABEL_SCOPE_KEY = "springagent.io/scope-key";
  public static final String LABEL_SHELL_POD_ROLE = "springagent.io/shell-pod-role";
  public static final String SHELL_POD_ROLE_ADMIN = "admin";
  public static final String CONTAINER_NAME = "shell";
  public static final String CREDENTIALS_VOLUME_NAME = "user-credentials";

  private final KubernetesClient kubernetesClient;
  private final KubernetesShellProperties properties;
  private final Admins admins;

  /**
   * @param groupId the group (e.g. Feishu group chat id) this call came from, or null/blank for
   *     none. Pod identity is keyed on (userId, groupId, tenantId) together, so calls from a
   *     different group/tenant get their own Pod with that scope's mounts, instead of reusing a Pod
   *     that was created for a different scope and lacks them.
   * @param tenantId the tenant (e.g. Feishu tenant key) this call came from, or null/blank for
   *     none. Part of the Pod identity key, same as groupId.
   */
  public String ensurePodFor(final String userId, final String groupId, final String tenantId) {
    final var ns = namespace();
    final var scopeKey = scopeKey(userId, groupId, tenantId);
    final var existing = findRunningPod(ns, userId, scopeKey);
    if (existing.isPresent()) {
      return existing.get().getMetadata().getName();
    }

    final var jobName = jobName(scopeKey);
    createJob(ns, jobName, userId, groupId, tenantId, scopeKey);
    return waitForRunningPod(ns, userId, scopeKey, jobName);
  }

  private void createJob(
      final String ns,
      final String jobName,
      final String userId,
      final String groupId,
      final String tenantId,
      final String scopeKey) {
    try {
      kubernetesClient
          .batch()
          .v1()
          .jobs()
          .inNamespace(ns)
          .resource(buildJob(jobName, userId, groupId, tenantId, scopeKey))
          .create();
      log.info("Created shell sandbox Job {} for user {}", jobName, userId);
      return;
    } catch (final KubernetesClientException e) {
      if (e.getCode() != 409) throw e;
    }
    // 409: a Job with this name already exists. If it's a terminated leftover that the
    // TTL controller hasn't reaped yet, delete it and recreate; otherwise let the caller
    // wait for the existing Job's Pod to reach Running.
    final var existing =
        kubernetesClient.batch().v1().jobs().inNamespace(ns).withName(jobName).get();
    if (existing == null) {
      log.debug("Job {} disappeared after 409 for user {}; retrying create", jobName, userId);
      kubernetesClient
          .batch()
          .v1()
          .jobs()
          .inNamespace(ns)
          .resource(buildJob(jobName, userId, groupId, tenantId, scopeKey))
          .create();
      return;
    }
    if (isTerminated(existing)) {
      log.info("Found terminated Job {} for user {}; deleting and recreating", jobName, userId);
      kubernetesClient
          .batch()
          .v1()
          .jobs()
          .inNamespace(ns)
          .withName(jobName)
          .withPropagationPolicy(DeletionPropagation.FOREGROUND)
          .delete();
      try {
        kubernetesClient
            .batch()
            .v1()
            .jobs()
            .inNamespace(ns)
            .withName(jobName)
            .waitUntilCondition(j -> j == null, 30, TimeUnit.SECONDS);
      } catch (final RuntimeException e) {
        throw new IllegalStateException(
            "Timed out waiting for stale Job " + jobName + " to be deleted", e);
      }
      kubernetesClient
          .batch()
          .v1()
          .jobs()
          .inNamespace(ns)
          .resource(buildJob(jobName, userId, groupId, tenantId, scopeKey))
          .create();
      log.info("Recreated shell sandbox Job {} for user {}", jobName, userId);
    } else {
      log.debug("Job {} already exists for user {} (lost create race)", jobName, userId);
    }
  }

  private static boolean isTerminated(final Job job) {
    final var status = job.getStatus();
    if (status == null) return false;
    if (status.getSucceeded() != null && status.getSucceeded() > 0) return true;
    if (status.getFailed() != null && status.getFailed() > 0) return true;
    if (status.getConditions() != null) {
      for (final var c : status.getConditions()) {
        if (("Complete".equals(c.getType()) || "Failed".equals(c.getType()))
            && "True".equalsIgnoreCase(c.getStatus())) {
          return true;
        }
      }
    }
    return false;
  }

  private Optional<Pod> findRunningPod(
      final String ns, final String userId, final String scopeKey) {
    final var pods =
        kubernetesClient
            .pods()
            .inNamespace(ns)
            .withLabel(LABEL_OWNER_USER_ID, userId)
            .withLabel(LABEL_SCOPE_KEY, scopeKey)
            .list()
            .getItems();
    return pods.stream()
        .filter(p -> p.getStatus() != null && "Running".equals(p.getStatus().getPhase()))
        .findFirst();
  }

  private String waitForRunningPod(
      final String ns, final String userId, final String scopeKey, final String jobName) {
    final var timeoutSeconds = properties.startupTimeout().getSeconds();
    final var deadline = System.nanoTime() + properties.startupTimeout().toNanos();
    while (System.nanoTime() < deadline) {
      final var pod = findRunningPod(ns, userId, scopeKey);
      if (pod.isPresent()) {
        return pod.get().getMetadata().getName();
      }
      try {
        Thread.sleep(500);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for shell pod", e);
      }
    }
    throw new IllegalStateException(
        "Shell pod for user "
            + userId
            + " (job "
            + jobName
            + ") did not reach Running within "
            + timeoutSeconds
            + "s");
  }

  public String namespace() {
    if (properties.namespace() != null && !properties.namespace().isBlank()) {
      return properties.namespace();
    }
    return kubernetesClient.getNamespace();
  }

  static String jobName(final String scopeKey) {
    return "spring-agent-shell-" + scopeKey;
  }

  /**
   * A short hash identifying the (userId, groupId, tenantId) combination, safe for use as both a
   * Kubernetes resource-name suffix and a label value.
   */
  static String scopeKey(final String userId, final String groupId, final String tenantId) {
    return shortHash(
        userId + " " + (groupId == null ? "" : groupId) + " " + (tenantId == null ? "" : tenantId));
  }

  public String credentialsSecretName(final String userId) {
    return "spring-agent-shell-creds-" + shortHash(userId);
  }

  /**
   * Delete the user's shell Job (and its Pod) if one exists. Returns true if anything was deleted.
   */
  public boolean deletePodFor(final String userId) {
    final var ns = namespace();
    final var existing =
        kubernetesClient
            .batch()
            .v1()
            .jobs()
            .inNamespace(ns)
            .withLabel(LABEL_OWNER_USER_ID, userId)
            .list()
            .getItems();
    if (existing.isEmpty()) {
      return false;
    }
    for (final var job : existing) {
      kubernetesClient
          .batch()
          .v1()
          .jobs()
          .inNamespace(ns)
          .withName(job.getMetadata().getName())
          .withPropagationPolicy(DeletionPropagation.BACKGROUND)
          .delete();
      log.info("Deleted shell sandbox Job {} for user {}", job.getMetadata().getName(), userId);
    }
    return true;
  }

  @SneakyThrows
  private static String shortHash(final String input) {
    final var md = MessageDigest.getInstance("SHA-256");
    final var digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest).substring(0, 16);
  }

  private Job buildJob(
      final String jobName,
      final String userId,
      final String groupId,
      final String tenantId,
      final String scopeKey) {
    final var labels = new HashMap<String, String>();
    labels.put(LABEL_APP, LABEL_APP_VALUE);
    labels.put(LABEL_SHELL_POD, "true");
    labels.put(LABEL_OWNER_USER_ID, userId);
    labels.put(LABEL_SCOPE_KEY, scopeKey);
    if (admins.isAdmin(userId)) {
      labels.put(LABEL_SHELL_POD_ROLE, SHELL_POD_ROLE_ADMIN);
    }

    final var idleSeconds = properties.idleTimeout().getSeconds();
    final var watchdogScript =
        String.join(
            "\n",
            "set -e",
            "mkdir -p /tmp/.bg",
            "touch /tmp/.last_activity",
            "while sleep 30; do",
            "  age=$(( $(date +%s) - $(stat -c %Y /tmp/.last_activity) ))",
            "  if [ \"$age\" -gt \"$IDLE_TTL_SECONDS\" ]; then",
            "    echo \"shell sandbox idle for ${age}s, exiting\"",
            "    exit 0",
            "  fi",
            "done");

    final var imagePullSecrets =
        properties.imagePullSecrets().stream()
            .map(name -> new LocalObjectReferenceBuilder().withName(name).build())
            .toList();

    final var credentialsSecretName = credentialsSecretName(userId);
    final var credentialsMountPath = properties.credentials().mountPathOrDefault();
    final var mounts = properties.storage().mounts();
    final var homeDir = Path.of(properties.workingDir(), userId).toString();

    var podSpecBuilder =
        new PodSpecBuilder()
            .withRestartPolicy("Never")
            .withAutomountServiceAccountToken(false)
            .withImagePullSecrets(imagePullSecrets);
    if (properties.fsGroup() != null) {
      podSpecBuilder =
          podSpecBuilder
              .withNewSecurityContext()
              .withFsGroup(properties.fsGroup())
              .endSecurityContext();
    }
    var podSpec =
        podSpecBuilder
            .addNewContainer()
            .withName(CONTAINER_NAME)
            .withImage(properties.image())
            .withImagePullPolicy("Always")
            .withWorkingDir(homeDir)
            .withCommand("sh", "-c", watchdogScript)
            .addNewEnv()
            .withName("IDLE_TTL_SECONDS")
            .withValue(Long.toString(idleSeconds))
            .endEnv()
            .addNewEnv()
            .withName("HOME")
            .withValue(homeDir)
            .endEnv()
            .withNewResources()
            .withRequests(
                Map.of(
                    "cpu", Quantity.parse(properties.resources().cpuRequestOrDefault()),
                    "memory", Quantity.parse(properties.resources().memoryRequestOrDefault())))
            .withLimits(
                Map.of(
                    "cpu", Quantity.parse(properties.resources().cpuLimitOrDefault()),
                    "memory", Quantity.parse(properties.resources().memoryLimitOrDefault())))
            .endResources()
            .addNewVolumeMount()
            .withName(CREDENTIALS_VOLUME_NAME)
            .withMountPath(credentialsMountPath)
            .withReadOnly(true)
            .endVolumeMount()
            .endContainer()
            .addNewVolume()
            .withName(CREDENTIALS_VOLUME_NAME)
            .withNewSecret()
            .withSecretName(credentialsSecretName)
            .withOptional(true)
            .withDefaultMode(0400)
            .endSecret()
            .endVolume()
            .build();

    final var builder = new PodSpecBuilder(podSpec);

    // Storage: mounted once per in-scope id, personal always included, group/tenant only when
    // present at the time this Pod is created — a sibling of the personal mount under the same
    // mountPath, e.g. mountPath/groups/{groupId} and mountPath/tenant/{tenantId} next to
    // mountPath/{userId}.
    final var scopeIds = new ArrayList<String>();
    scopeIds.add(userId);
    if (groupId != null && !groupId.isBlank()) {
      scopeIds.add(Path.of("groups", groupId).toString());
    }
    if (tenantId != null && !tenantId.isBlank()) {
      scopeIds.add(Path.of("tenant", tenantId).toString());
    }
    for (var s = 0; s < scopeIds.size(); s++) {
      final var scopeId = scopeIds.get(s);
      for (var i = 0; i < mounts.size(); i++) {
        final var mount = mounts.get(i);
        final var volumeName = "mount-" + s + "-" + i;
        builder
            .editFirstContainer()
            .addNewVolumeMount()
            .withName(volumeName)
            .withMountPath(Path.of(mount.mountPath(), scopeId).toString())
            .withSubPath(mount.subPath(scopeId))
            .endVolumeMount()
            .endContainer()
            .addNewVolume()
            .withName(volumeName)
            .withNewPersistentVolumeClaim()
            .withClaimName(mount.pvcName())
            .endPersistentVolumeClaim()
            .endVolume();
      }
    }

    // Credentials: one optional envFrom Secret per in-scope id, added least-specific-first (tenant,
    // then group, then the personal one last) so a key present in more than one Secret resolves to
    // the most specific one — Kubernetes envFrom lets a later source's key win over an earlier one.
    if (tenantId != null && !tenantId.isBlank()) {
      builder
          .editFirstContainer()
          .addNewEnvFrom()
          .withNewSecretRef()
          .withName(credentialsSecretName(Path.of("tenant", tenantId).toString()))
          .withOptional(true)
          .endSecretRef()
          .endEnvFrom()
          .endContainer();
    }
    if (groupId != null && !groupId.isBlank()) {
      builder
          .editFirstContainer()
          .addNewEnvFrom()
          .withNewSecretRef()
          .withName(credentialsSecretName(Path.of("groups", groupId).toString()))
          .withOptional(true)
          .endSecretRef()
          .endEnvFrom()
          .endContainer();
    }
    builder
        .editFirstContainer()
        .addNewEnvFrom()
        .withNewSecretRef()
        .withName(credentialsSecretName)
        .withOptional(true)
        .endSecretRef()
        .endEnvFrom()
        .endContainer();

    podSpec = builder.build();

    final var podTemplate =
        new PodTemplateSpecBuilder()
            .withNewMetadata()
            .withLabels(labels)
            .addToAnnotations("sidecar.istio.io/inject", "false")
            .endMetadata()
            .withSpec(podSpec)
            .build();

    return new JobBuilder()
        .withNewMetadata()
        .withName(jobName)
        .withLabels(labels)
        .endMetadata()
        .withNewSpec()
        .withCompletions(1)
        .withParallelism(1)
        .withBackoffLimit(0)
        .withTtlSecondsAfterFinished(60)
        .withActiveDeadlineSeconds(properties.hardDeadline().getSeconds())
        .withTemplate(podTemplate)
        .endSpec()
        .build();
  }
}
