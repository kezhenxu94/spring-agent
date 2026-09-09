package me.kezhenxu94.springagent.tools.shell.docker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
import me.kezhenxu94.springagent.core.tools.credentials.ShellCredentialStore;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The per-user sandbox container, and its lifecycle.
 *
 * <p>The counterpart of {@code UserPodManager} in the Kubernetes module, and it deliberately keeps
 * that class's shape: one sandbox per scope, created on first use, its working directory the user's
 * own home under {@code app.storage.location}, and a watchdog inside the container that exits when
 * the user stops using it.
 *
 * <p><b>Per scope, not per user</b>, for the reason that class gives: a sandbox carries the group's
 * and the tenant's homes as well as the personal one, so a container built for a user in one group
 * chat has the wrong directories mounted for the same user in another. Keying the registry on the
 * (userId, groupId, tenantId) triple is what stops one being handed to the other.
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
  public static final String LABEL_OWNER_GROUP_ID = "springagent.io/owner-group-id";
  public static final String LABEL_OWNER_TENANT_ID = "springagent.io/owner-tenant-id";
  public static final String LABEL_SHELL_CONTAINER_ROLE = "springagent.io/shell-container-role";
  public static final String SHELL_CONTAINER_ROLE_ADMIN = "admin";

  /** What {@code docker ps} leads with, so a sandbox is recognisable without reading its labels. */
  public static final String CONTAINER_NAME_PREFIX = "spring-agent-shell-";

  private static final Pattern ENV_VAR_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

  /**
   * Everything Docker rejects in a container name. The prefix covers the leading-character rule.
   */
  private static final Pattern UNSAFE_IN_NAME = Pattern.compile("[^A-Za-z0-9_.-]");

  private static final int MAX_USER_ID_IN_NAME = 64;

  private final DockerShellProperties properties;
  private final StorageProperties storageProperties;
  private final Admins admins;
  private final ShellCredentialStore credentialStore;

  private final ConcurrentMap<String, GenericContainer<?>> containers = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

  /**
   * Which sandbox a request means. Not a hash, unlike {@code UserPodManager.scopeKey}: that one has
   * to survive DNS-1123 as a resource name, whereas this is only ever a key in the two maps above,
   * so the ids can stay readable in a heap dump. NUL separates them because no id contains one, and
   * so no two different triples can spell the same key.
   */
  private static boolean present(final String id) {
    return id != null && !id.isBlank();
  }

  private static String nullToEmpty(final String id) {
    return id == null ? "" : id;
  }

  private static String scopeKey(final String userId, final String groupId, final String tenantId) {
    return userId + '\0' + nullToEmpty(groupId) + '\0' + nullToEmpty(tenantId);
  }

  /**
   * The sandbox for this scope, started if it was not already. Serialised per scope rather than
   * globally, so one user's image pull does not hold up everyone else's commands.
   */
  public GenericContainer<?> ensureContainerFor(
      final String userId, final String groupId, final String tenantId) {
    final var key = scopeKey(userId, groupId, tenantId);
    synchronized (locks.computeIfAbsent(key, k -> new Object())) {
      final var existing = containers.get(key);
      if (existing != null && existing.isRunning()) {
        return existing;
      }
      if (existing != null) {
        // The watchdog exited on idle or the hard deadline, or someone stopped it by hand.
        log.info("Shell sandbox container for user {} is gone; starting a fresh one", userId);
        discard(existing);
        containers.remove(key);
      }
      final var created = start(userId, groupId, tenantId);
      containers.put(key, created);
      return created;
    }
  }

  /**
   * Stop this scope's sandbox if one is running. Returns whether there was anything to stop.
   *
   * <p>This scope's and no other: the same user in another group chat has a sandbox of their own,
   * with different directories mounted, and restarting the one they are sitting in must not take
   * that one with it.
   */
  public boolean deleteContainerFor(
      final String userId, final String groupId, final String tenantId) {
    final var key = scopeKey(userId, groupId, tenantId);
    synchronized (locks.computeIfAbsent(key, k -> new Object())) {
      final var existing = containers.remove(key);
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
   * model paths under {@code app.storage.location}, and the upload and file-serving paths read them
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

  private GenericContainer<?> start(
      final String userId, final String groupId, final String tenantId) {
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
            .withLabels(labels(userId, groupId, tenantId))
            .withFileSystemBind(userHome, userHome, BindMode.READ_WRITE)
            .withWorkingDirectory(userHome)
            // In memory, never on the host: a credential exists on disk nowhere except the
            // database it came from, and goes away when the container does.
            .withTmpFs(Map.of(credentialsMountPath, "rw,noexec,nosuid,size=1m"))
            .withEnv(credentials)
            .withEnv("IDLE_TTL_SECONDS", Long.toString(properties.idleTimeout().getSeconds()))
            .withEnv("MAX_LIFETIME_SECONDS", Long.toString(properties.hardDeadline().getSeconds()))
            .withCreateContainerCmdModifier(
                cmd -> {
                  cmd.withName(containerName(userId));
                  cmd.getHostConfig()
                      .withMemory(properties.resources().memoryBytes())
                      .withNanoCPUs(properties.resources().nanoCpus());
                })
            .withStartupTimeout(properties.startupTimeout())
            .withCommand("sh", "-c", watchdogScript());

    for (final var shared : sharedHomes(groupId, tenantId)) {
      container.withFileSystemBind(shared, shared, BindMode.READ_WRITE);
    }

    if (properties.network() != null && !properties.network().isBlank()) {
      container.withNetworkMode(properties.network());
    }

    container.start();
    log.info("Started shell sandbox container {} for user {}", container.getContainerId(), userId);

    writeCredentialFiles(container, credentials, credentialsMountPath);
    return container;
  }

  /**
   * The group's and the tenant's homes, where this request has them and something has already been
   * written there — bound at the same path inside and outside, exactly as the personal one is.
   *
   * <p>Without these, {@code FileSystemTools} is allowed into all three homes while the shell can
   * only see one, so {@code Read} of a shared file works and {@code cat} of the same path reports
   * that it does not exist. {@code UserPodManager} has mounted all three all along; this is the
   * Docker backend catching up.
   *
   * <p><b>Only ones that already exist</b>, and this is not an optimisation. A bind mount whose
   * source is missing has Docker create it, owned by root, which is both a directory materialised
   * in shared storage on behalf of somebody who may only have been reading and one the application
   * itself then cannot write to. So a group's home reaches the sandbox from the first container
   * started after the group has anything in it — the same "present at the time this Pod is created"
   * rule {@code UserPodManager} states, and the reason a restart is what picks up a home that
   * appeared mid-session.
   */
  private List<String> sharedHomes(final String groupId, final String tenantId) {
    final var homes = new ArrayList<String>();
    if (present(groupId)) {
      addIfPresent(homes, Path.of("groups", groupId).toString());
    }
    if (present(tenantId)) {
      addIfPresent(homes, Path.of("tenant", tenantId).toString());
    }
    return homes;
  }

  private void addIfPresent(final List<String> homes, final String scopeId) {
    final var home = Path.of(storageProperties.getLocation(), scopeId).toAbsolutePath();
    if (Files.isDirectory(home)) {
      homes.add(home.toString());
    }
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
   * {@code spring-agent-shell-<user>-<random>}, rather than the name Docker would invent.
   *
   * <p>The prefix and the owner are there to be read: {@code docker ps} on a host running this
   * shows which containers are agent sandboxes and whose, without anyone having to know the label
   * scheme. The random tail is what keeps the name from being a liability — a name derived only
   * from the user would collide with a leftover container whenever this process died hard enough
   * that Ryuk went with it, turning a stale sandbox into a failure to start a new one. The labels
   * remain the thing to query; this is the thing to glance at.
   *
   * <p>Unlike {@code UserPodManager}, the user id goes in as written where it can. Kubernetes
   * hashes because DNS-1123 forbids the underscore that every Feishu open id contains; Docker
   * allows it, so hashing here would cost the legibility that is the whole point.
   */
  private static String containerName(final String userId) {
    final var safe = UNSAFE_IN_NAME.matcher(userId).replaceAll("-");
    final var trimmed =
        safe.length() > MAX_USER_ID_IN_NAME ? safe.substring(0, MAX_USER_ID_IN_NAME) : safe;
    final var suffix = Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFL);
    return CONTAINER_NAME_PREFIX + trimmed + "-" + suffix;
  }

  /**
   * Whether a name is safe to paste into the script above. {@code CredentialTools} already enforces
   * this on the way in; checking again here means a store that did not cannot inject shell.
   */
  private static boolean isEnvVarName(final String name) {
    return name != null && ENV_VAR_NAME.matcher(name).matches();
  }

  private Map<String, String> labels(
      final String userId, final String groupId, final String tenantId) {
    final var labels = new HashMap<String, String>();
    labels.put(LABEL_APP, LABEL_APP_VALUE);
    labels.put(LABEL_SHELL_CONTAINER, "true");
    labels.put(LABEL_OWNER_USER_ID, userId);
    // The ids as written rather than a scope hash, so that `docker ps --filter label=...` answers
    // "whose sandbox is this, and for which chat" without anyone having to reproduce a hash.
    if (present(groupId)) {
      labels.put(LABEL_OWNER_GROUP_ID, groupId);
    }
    if (present(tenantId)) {
      labels.put(LABEL_OWNER_TENANT_ID, tenantId);
    }
    if (admins.isAdmin(userId)) {
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
