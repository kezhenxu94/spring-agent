package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;

/**
 * {@link MediaSources}, which is what lets a tool be handed a filesystem path by a model.
 *
 * <p>The confinement tests are the load-bearing ones and are not optional: {@code GenerateImage}'s
 * tool description now invites the model to pass local paths, and the model gets those from a
 * conversation, so a path outside the asking user's own workspace has to be refused rather than
 * read.
 */
class MediaSourcesTest {

  @TempDir Path workspace;
  @TempDir Path elsewhere;

  private MockWebServer server;
  private MediaSources mediaSources;
  private HomeDir home;

  private static final byte[] PNG = "pretend-png-bytes".getBytes(StandardCharsets.UTF_8);

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    mediaSources = new MediaSources(new RestTemplate());
    home = new UserHome(workspace);
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  private Path anImageInTheWorkspace(final String name) throws Exception {
    final var file = home.artifacts().resolve(name);
    Files.write(file, PNG);
    return file;
  }

  // --- local files ------------------------------------------------------------------------

  @Test
  @DisplayName("an absolute path inside the workspace becomes bytes")
  void aLocalPath() throws Exception {
    final var file = anImageInTheWorkspace("generated-1.png");

    final var media = mediaSources.resolve(file.toString(), "u1", home);

    assertThat(media).isNotNull();
    assertThat(media.getData()).isInstanceOf(byte[].class).isEqualTo(PNG);
    assertThat(media.getName()).isEqualTo("generated-1.png");
    assertThat(server.getRequestCount()).as("nothing local should be fetched").isZero();
  }

  @Test
  @DisplayName(
      "a file:// URL names the same file, which is the spelling GenerateImage answers with")
  void aFileUrl() throws Exception {
    final var file = anImageInTheWorkspace("generated-2.png");

    // The case that failed before this class existed: Path.of("file:///…") is a relative path whose
    // first segment is the literal "file:", so it fell the confinement check and was dropped with a
    // warning about a workspace — while being the very URL GenerateImage had just returned.
    final var media = mediaSources.resolve(file.toUri().toString(), "u1", home);

    assertThat(media).isNotNull();
    assertThat(media.getData()).isEqualTo(PNG);
    assertThat(media.getName()).isEqualTo("generated-2.png");
  }

  @Test
  @DisplayName("a file:// URL whose name needed encoding round-trips")
  void anEncodedFileUrl() throws Exception {
    final var file = anImageInTheWorkspace("a picture (final).png");

    // Why the URI is parsed rather than the prefix trimmed: trimming leaves %20 in the path and the
    // file is then reported missing.
    final var media = mediaSources.resolve(file.toUri().toString(), "u1", home);

    assertThat(media).isNotNull();
    assertThat(media.getName()).isEqualTo("a picture (final).png");
  }

  @Test
  @DisplayName("the mime type is probed rather than claimed")
  void mimeTypeIsProbed() throws Exception {
    final var file = home.artifacts().resolve("notes.txt");
    Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

    final var media = mediaSources.resolve(file.toString(), "u1", home);

    assertThat(media).isNotNull();
    assertThat(media.getMimeType().toString()).startsWith("text/");
  }

  // --- confinement ------------------------------------------------------------------------

  @Test
  @DisplayName("a path outside the workspace is refused")
  void aPathOutsideTheWorkspaceIsRefused() throws Exception {
    final var outside = elsewhere.resolve("someone-elses.png");
    Files.write(outside, PNG);

    assertThat(mediaSources.resolve(outside.toString(), "u1", home)).isNull();
    assertThat(mediaSources.resolve(outside.toUri().toString(), "u1", home))
        .as("a file:// URL must not be a way around the same check")
        .isNull();
  }

  @Test
  @DisplayName("traversal out of the workspace is refused")
  void traversalIsRefused() throws Exception {
    final var outside = elsewhere.resolve("someone-elses.png");
    Files.write(outside, PNG);

    // Normalised before the check, so ../ cannot walk out and back in under a name that passes.
    final var traversed =
        workspace
            .resolve("artifacts")
            .resolve("..")
            .resolve("..")
            .resolve(elsewhere.getFileName())
            .resolve("someone-elses.png");

    assertThat(mediaSources.resolve(traversed.toString(), "u1", home)).isNull();
  }

  @Test
  @DisplayName("a path that does not exist is not read, and says so differently")
  void aMissingFile() {
    assertThat(mediaSources.resolve(workspace.resolve("nope.png").toString(), "u1", home)).isNull();
  }

  // --- remote -----------------------------------------------------------------------------

  @Test
  @DisplayName("an http URL is downloaded")
  void anHttpUrl() throws Exception {
    server.enqueue(new MockResponse().setBody("downloaded"));
    final var url = server.url("/cat.png").toString();

    final var media = mediaSources.resolve(url, "u1", home);

    assertThat(media).isNotNull();
    assertThat(media.getData()).isEqualTo("downloaded".getBytes(StandardCharsets.UTF_8));
    assertThat(media.getName()).isEqualTo(url);
    assertThat(server.takeRequest().getPath()).isEqualTo("/cat.png");
  }

  @Test
  @DisplayName("a URL that answers nothing is null, not an exception")
  void anEmptyDownload() {
    server.enqueue(new MockResponse().setBody(""));
    assertThat(mediaSources.resolve(server.url("/empty.png").toString(), "u1", home)).isNull();
  }

  @Test
  @DisplayName("a URL that fails is null, not an exception")
  void aFailedDownload() {
    // Never throws, by contract: one unreadable source must not cost the caller the ones that did
    // resolve, and what a missing one means is the caller's decision.
    server.enqueue(new MockResponse().setResponseCode(500));
    assertThat(mediaSources.resolve(server.url("/boom.png").toString(), "u1", home)).isNull();
  }

  @Test
  @DisplayName("a blank source is ignored rather than treated as a path")
  void aBlankSource() {
    assertThat(mediaSources.resolve(null, "u1", home)).isNull();
    assertThat(mediaSources.resolve("", "u1", home)).isNull();
    assertThat(mediaSources.resolve("   ", "u1", home)).isNull();
  }
}
