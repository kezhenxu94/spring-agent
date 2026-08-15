package me.kezhenxu94.springagent.tools.shell.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
import me.kezhenxu94.springagent.core.tools.credentials.ShellCredentialStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The Docker shell against a real daemon.
 *
 * <p>The wiring is covered by {@code ShellBackendSelectionTest} in the application module; what is
 * only provable here is that the pieces borrowed from the Kubernetes shell — the background-shell
 * bookkeeping under {@code /tmp/.bg}, the idle watchdog's touch file, the credentials tmpfs — still
 * work when the exec channel underneath them is Docker's rather than the API server's.
 */
class DockerShellToolsIntegrationTest {

  /**
   * The base {@code docker/shell-runner} is built on, rather than the published runner itself:
   * these tests exercise the shell against the floor it needs — bash, GNU coreutils for {@code stat
   * -c} and {@code dd}, util-linux for {@code setsid} — and nothing that image adds on top. Pulled
   * from a public registry, so this runs before that image exists anywhere.
   */
  private static final String IMAGE = "debian:trixie-slim";

  private static final String USER_ID = "u1";
  private static final ToolContext CONTEXT = new ToolContext(Map.of("userId", USER_ID));

  @TempDir Path storage;

  private UserContainerManager manager;

  @AfterEach
  void stopContainers() {
    if (manager != null) {
      manager.close();
    }
  }

  @Test
  @DisplayName("a command runs in the user's own directory, and the sandbox is reused")
  void runsCommandsInTheUsersHome() {
    final var tools = tools(Map.of());

    final var pwd = tools.bash("pwd", null, null, null, CONTEXT);

    assertThat(pwd).contains(storage.resolve(USER_ID).toString());
    // Written from inside the container, read back from the host: the bind mount is what makes the
    // agent's files reachable by the rest of the application.
    tools.bash("echo hello > from-the-sandbox.txt", null, null, null, CONTEXT);
    assertThat(storage.resolve(USER_ID).resolve("from-the-sandbox.txt")).exists();

    final var first = manager.ensureContainerFor(USER_ID);
    assertThat(manager.ensureContainerFor(USER_ID)).isSameAs(first);
  }

  @Test
  @DisplayName("the container says whose sandbox it is, even for a user id Docker would reject")
  void namesContainersAfterTheirOwner() {
    tools(Map.of());

    // The underscore every Feishu open id carries is legal in a Docker name, so it survives as
    // written — this is the case that has to stay readable.
    assertThat(manager.ensureContainerFor("ou_7f40fefc8ddae").getContainerName())
        .startsWith("/" + UserContainerManager.CONTAINER_NAME_PREFIX + "ou_7f40fefc8ddae-");
    // `@` is not. An id carrying one still starts, under a sanitised name rather than a rejected
    // one.
    assertThat(manager.ensureContainerFor("user@example").getContainerName())
        .startsWith("/" + UserContainerManager.CONTAINER_NAME_PREFIX + "user-example-");
  }

  @Test
  @DisplayName("a failing command reports its exit code and stderr")
  void reportsFailures() {
    final var tools = tools(Map.of());

    final var result = tools.bash("echo boom >&2; exit 3", null, null, null, CONTEXT);

    assertThat(result).contains("STDERR:").contains("boom").contains("Exit code: 3");
  }

  @Test
  @DisplayName("credentials arrive as environment variables and as files, and never touch the host")
  void deliversCredentials() {
    final var tools = tools(Map.of("GITHUB_TOKEN", "ghp_secret"));

    assertThat(tools.bash("printenv GITHUB_TOKEN", null, null, null, CONTEXT))
        .contains("ghp_secret");
    assertThat(tools.bash("cat /run/secrets/credentials/GITHUB_TOKEN", null, null, null, CONTEXT))
        .contains("ghp_secret");
    // The mount is a tmpfs, so the value lives in memory rather than in the container's layer.
    assertThat(tools.bash("stat -f -c %T /run/secrets/credentials", null, null, null, CONTEXT))
        .contains("tmpfs");
    assertThat(storage.resolve(USER_ID).resolve("GITHUB_TOKEN")).doesNotExist();
  }

  @Test
  @DisplayName("a background shell streams output incrementally and can be killed")
  void runsBackgroundShells() throws Exception {
    final var tools = tools(Map.of());

    final var started =
        tools.bash(
            "for i in 1 2 3 4 5 6 7 8 9 10; do echo line-$i; sleep 1; done",
            null,
            null,
            true,
            CONTEXT);
    assertThat(started).contains("Background shell started with ID: ");
    final var bashId = started.substring("bash_id: ".length(), started.indexOf('\n'));

    final var firstRead = awaitOutput(tools, bashId, "line-1");
    assertThat(firstRead).contains("Status: Running");
    // Only what is new since the last call: line-1 was already handed over above.
    assertThat(awaitOutput(tools, bashId, "line-2")).doesNotContain("line-1");

    assertThat(tools.killShell(bashId, CONTEXT)).contains("Successfully killed shell");
    assertThat(tools.bashOutput(bashId, null, CONTEXT)).contains("No background shell found");
  }

  @Test
  @DisplayName("an unknown background shell is an error, not an empty answer")
  void rejectsUnknownShells() {
    final var tools = tools(Map.of());
    tools.bash("true", null, null, null, CONTEXT);

    assertThat(tools.bashOutput("shell_does_not_exist", null, CONTEXT))
        .contains("No background shell found");
    assertThat(tools.bashOutput("../etc/passwd", null, CONTEXT))
        .isEqualTo("Error: invalid bash_id");
  }

  /** Polls the way the model would, rather than assuming a fixed amount of output has appeared. */
  private String awaitOutput(final DockerShellTools tools, final String bashId, final String needle)
      throws InterruptedException {
    final var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      final var output = tools.bashOutput(bashId, null, CONTEXT);
      if (output.contains(needle)) {
        return output;
      }
      Thread.sleep(500);
    }
    throw new AssertionError("Background shell never produced " + needle);
  }

  private DockerShellTools tools(final Map<String, String> credentials) {
    final var properties =
        new DockerShellProperties(
            IMAGE,
            null,
            Duration.ofMinutes(30),
            Duration.ofHours(4),
            Duration.ofMinutes(2),
            30_000,
            120_000L,
            600_000L,
            new DockerShellProperties.Resources(null, null),
            new DockerShellProperties.Credentials(null, "unused-but-required"));
    manager =
        new UserContainerManager(
            properties, storageProperties(), appProperties(), fixedStore(credentials));
    return new DockerShellTools(manager, properties);
  }

  private StorageProperties storageProperties() {
    return new StorageProperties() {
      @Override
      public String getLocation() {
        return storage.toString();
      }

      @Override
      public String getBaseUrl() {
        return "";
      }

      @Override
      public String getCdnUrl() {
        return "";
      }

      @Override
      public boolean isAutoUnzip() {
        return false;
      }
    };
  }

  private static SpringAgentProperties appProperties() {
    return new SpringAgentProperties(
        null,
        new SpringAgentProperties.Ai(null, Set.of(), null, null, null, "system prompt", null),
        null,
        false);
  }

  /**
   * Stands in for whichever store a deployment configured; this test is about delivery, not keys.
   */
  private static ShellCredentialStore fixedStore(final Map<String, String> credentials) {
    return new ShellCredentialStore() {
      @Override
      public void put(final String userId, final String name, final String value) {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<Entry> list(final String userId) {
        return credentials.keySet().stream().map(name -> new Entry(name, null)).toList();
      }

      @Override
      public boolean delete(final String userId, final String name) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Map<String, String> resolve(final String userId) {
        return credentials;
      }
    };
  }
}
