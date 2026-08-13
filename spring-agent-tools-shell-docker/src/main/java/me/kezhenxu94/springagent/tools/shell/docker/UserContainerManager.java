package me.kezhenxu94.springagent.tools.shell.docker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
import me.kezhenxu94.springagent.core.tools.credentials.ShellCredentialStore;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The per-user sandbox container, and its lifecycle.
 *
 * <p>The counterpart of {@code UserPodManager} in the Kubernetes module, and it deliberately keeps
 * that class's shape: one sandbox per user, created on first use, its working directory the user's
 * own home under {@code storage.location}, and a watchdog inside the container that exits when the
 * user stops using it.
 *
 * <p>Where the two differ is what outlives this process. A Job's Pod is the cluster's, and a
 * restarted application finds it again by label; a container started through Testcontainers belongs
 * to Ryuk, which kills it when this JVM exits. Nothing here tries to paper over that — {@link
 * #containers} is the whole registry, and it dies with the process that owns it.
 */
@Slf4j
@RequiredArgsConstructor
public class UserContainerManager implements AutoCloseable {

  public static final String LABEL_APP = "app";
  public static final String LABEL_APP_VALUE = "spring-agent-shell";
  public static final String LABEL_SHELL_CONTAINER = "springagent.io/shell-container";
  public static final String LABEL_OWNER_USER_ID = "springagent.io/owner-user-id";
  public static final String LABEL_SHELL_CONTAINER_ROLE = "springagent.io/shell-container-role";
  public static final String SHELL_CONTAINER_ROLE_ADMIN = "admin";

  private static final Pattern ENV_VAR_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

  private final DockerShellProperties properties;
  private final StorageProperties storageProperties;
  private final SpringAgentProperties appConfiguration;
  private final ShellCredentialStore credentialStore;

  private final ConcurrentMap<String, GenericContainer<?>> containers = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

  /**
   * The user's sandbox, started if it was not already. Serialised per user rather than globally, so
   * one user's image pull does not hold up everyone else's commands.
   */
  public GenericContainer<?> ensureContainerFor(final String userId) {
    synchronized (locks.computeIfAbsent(userId, key -> new Object())) {
      final var existing = containers.get(userId);
      if (existing != null && existing.isRunning()) {
        return existing;
      }
      if (existing != null) {
        // The watchdog exited on idle or the hard deadline, or someone stopped it by hand.
        log.info("Shell sandbox container for user {} is gone; starting a fresh one", userId);
        discard(existing);
        containers.remove(userId);
      }
      final var created = start(userId);
      containers.put(userId, created);
      return created;
    }
  }

  /** Stop the user's sandbox if one is running. Returns whether there was anything to stop. */
  public boolean deleteContainerFor(final String userId) {
    synchronized (locks.computeIfAbsent(userId, key -> new Object())) {
      final var existing = containers.remove(userId);
      if (existing == null) {
        return false;
      }
      discard(existing);
      log.info("Stopped shell sandbox container for user {}", userId);
      return true;
    }
  }

  /**
   * Where the user's files live, identical inside and outside the container.
   *
   * <p>Bound at the same path on both sides on purpose: {@code UserWorkspaceFactory} hands the
   * model paths under {@code storage.location}, and the upload and file-serving paths read them
   * back from the host, so a container-local path would make the two disagree about the same file.
   */
  public String userHome(final String userId) {
    return Path.of(storageProperties.getLocation(), userId).toAbsolutePath().toString();
  }

  @Override
  public void close() {
    containers.values().forEach(this::discard);
    containers.clear();
  }

  private GenericContainer<?> start(final String userId) {
    final var userHome = userHome(userId);
    try {
      Files.createDirectories(Path.of(userHome));
    } catch (final IOException e) {
      throw new IllegalStateException("Cannot create the home directory for user " + userId, e);
    }

    final var credentials = credentialStore.resolve(userId);
    final var credentialsMountPath = properties.credentials().mountPathOrDefault();

    final var container =
        new GenericContainer<>(DockerImageName.parse(properties.image()))
            .withLabels(labels(userId))
            .withFileSystemBind(userHome, userHome, BindMode.READ_WRITE)
            .withWorkingDirectory(userHome)
            // In memory, never on the host: a credential exists on disk nowhere except the
            // database it came from, and goes away when the container does.
            .withTmpFs(Map.of(credentialsMountPath, "rw,noexec,nosuid,size=1m"))
            .withEnv(credentials)
            .withEnv("IDLE_TTL_SECONDS", Long.toString(properties.idleTimeout().getSeconds()))
            .withEnv("MAX_LIFETIME_SECONDS", Long.toString(properties.hardDeadline().getSeconds()))
            .withCreateContainerCmdModifier(
                cmd ->
                    cmd.getHostConfig()
                        .withMemory(properties.resources().memoryBytes())
                        .withNanoCPUs(properties.resources().nanoCpus()))
            .withStartupTimeout(properties.startupTimeout())
            .withCommand("sh", "-c", watchdogScript());

    if (properties.network() != null && !properties.network().isBlank()) {
      container.withNetworkMode(properties.network());
    }

    container.start();
    log.info("Started shell sandbox container {} for user {}", container.getContainerId(), userId);

    writeCredentialFiles(container, credentials, credentialsMountPath);
    return container;
  }

  /**
   * The same credentials the container already carries as environment variables, as files under the
   * mount path — for the tools that read a credential from a file rather than the environment.
   *
   * <p>Written by the container from its own environment, rather than copied in. Two reasons, and
   * the first is not a preference: Docker's copy-to-container writes into the container's
   * filesystem layer, so a destination underneath a tmpfs mount lands in the directory the mount is
   * hiding and is invisible from inside. The second is that only credential names travel in this
   * script — the values are already in the container and never appear in an argument list, an exec
   * record, or anything this application logs.
   */
  private void writeCredentialFiles(
      final GenericContainer<?> container,
      final Map<String, String> credentials,
      final String mountPath) {
    final var names =
        credentials.keySet().stream().filter(UserContainerManager::isEnvVarName).toList();
    if (names.isEmpty()) {
      return;
    }

    final var script = new StringBuilder("set -e\numask 077\n");
    for (final var name : names) {
      script
          .append("printf '%s' \"$")
          .append(name)
          .append("\" > \"")
          .append(mountPath)
          .append('/')
          .append(name)
          .append("\"\n");
    }
    script.append("chmod 400 \"").append(mountPath).append("\"/*\n");

    try {
      final var result = container.execInContainer("sh", "-c", script.toString());
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(
            "Failed to write credential files into the sandbox: " + result.getStderr());
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while writing credential files", e);
    } catch (final IOException e) {
      throw new IllegalStateException("Failed to write credential files into the sandbox", e);
    }
  }

  /**
   * Whether a name is safe to paste into the script above. {@code CredentialTools} already enforces
   * this on the way in; checking again here means a store that did not cannot inject shell.
   */
  private static boolean isEnvVarName(final String name) {
    return name != null && ENV_VAR_NAME.matcher(name).matches();
  }

  private Map<String, String> labels(final String userId) {
    final var labels = new HashMap<String, String>();
    labels.put(LABEL_APP, LABEL_APP_VALUE);
    labels.put(LABEL_SHELL_CONTAINER, "true");
    labels.put(LABEL_OWNER_USER_ID, userId);
    if (appConfiguration.ai().admins().contains(userId)) {
      labels.put(LABEL_SHELL_CONTAINER_ROLE, SHELL_CONTAINER_ROLE_ADMIN);
    }
    return labels;
  }

  /**
   * {@code UserPodManager}'s watchdog, plus the hard deadline that a Job gets for free from {@code
   * activeDeadlineSeconds}. Every tool call touches {@code /tmp/.last_activity}, so "idle" means
   * the user has stopped running commands, not that the commands themselves are quiet.
   */
  private static String watchdogScript() {
    return String.join(
        "\n",
        "set -e",
        "mkdir -p /tmp/.bg",
        "touch /tmp/.last_activity",
        "START=$(date +%s)",
        "while sleep 30; do",
        "  NOW=$(date +%s)",
        "  age=$(( NOW - $(stat -c %Y /tmp/.last_activity) ))",
        "  if [ \"$age\" -gt \"$IDLE_TTL_SECONDS\" ]; then",
        "    echo \"shell sandbox idle for ${age}s, exiting\"",
        "    exit 0",
        "  fi",
        "  if [ \"$(( NOW - START ))\" -gt \"$MAX_LIFETIME_SECONDS\" ]; then",
        "    echo \"shell sandbox reached its hard deadline, exiting\"",
        "    exit 0",
        "  fi",
        "done");
  }

  private void discard(final GenericContainer<?> container) {
    try {
      container.stop();
    } catch (final RuntimeException e) {
      log.warn("Failed to stop shell sandbox container {}", container.getContainerId(), e);
    }
  }
}
