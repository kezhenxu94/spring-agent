package me.kezhenxu94.springagent.core.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.PublishedResource;
import me.kezhenxu94.springagent.core.dao.repo.PublishedResourceRepo;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class ShareControllerTest {

  private static final String USER_ID = "user-1";
  private static final String TOKEN = "tok123";

  @TempDir Path storageRoot;

  FileSystemStorageService storageService;
  PublishedResourceRepo publishedResourceRepo;
  ShareController controller;

  @BeforeEach
  void setUp() {
    final var props =
        FileSystemStorageProperties.builder()
            .location(storageRoot.toString())
            .baseUrl("http://localhost:8080")
            .cdnUrl("http://localhost:8080")
            .build();
    storageService = new FileSystemStorageService(props);
    storageService.init();
    publishedResourceRepo = mock(PublishedResourceRepo.class);
    controller = new ShareController(storageService, publishedResourceRepo);
  }

  private void seed(
      final PublishedResource resource, final String relativePath, final String content)
      throws IOException {
    when(publishedResourceRepo.findById(resource.id())).thenReturn(Optional.of(resource));
    try (final var in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
      storageService.store(
          in,
          resource.visibility().name().toLowerCase()
              + "/"
              + USER_ID
              + "/"
              + resource.id()
              + "/"
              + relativePath);
    }
  }

  private MockHttpServletRequest requestFor(final String visibility, final String subPath) {
    final var request = new MockHttpServletRequest();
    request.setRequestURI("/share/" + visibility + "/" + USER_ID + "/" + TOKEN + "/" + subPath);
    return request;
  }

  private String bodyOf(final StreamingResponseBody body) throws IOException {
    final var out = new ByteArrayOutputStream();
    body.writeTo(out);
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("serves a public, non-expired single file inline")
  void servesPublicFile() throws IOException {
    final var resource =
        PublishedResource.builder()
            .id(TOKEN)
            .ownerId(USER_ID)
            .visibility(PublishedResource.Visibility.PUBLIC)
            .directory(false)
            .entryFilename("report.txt")
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    seed(resource, "report.txt", "hello world");

    final var response = new MockHttpServletResponse();
    final var body =
        controller.serve(
            PublishedResource.Visibility.PUBLIC,
            USER_ID,
            TOKEN,
            requestFor("public", "report.txt"),
            response);

    assertThat(bodyOf(body)).isEqualTo("hello world");
    assertThat(response.getHeader("Content-Disposition")).isEqualTo("inline");
  }

  @Test
  @DisplayName("returns 410 Gone for an expired resource")
  void returns410WhenExpired() throws IOException {
    final var resource =
        PublishedResource.builder()
            .id(TOKEN)
            .ownerId(USER_ID)
            .visibility(PublishedResource.Visibility.PUBLIC)
            .directory(false)
            .entryFilename("report.txt")
            .expiresAt(Instant.now().minusSeconds(60))
            .build();
    seed(resource, "report.txt", "hello world");

    final var response = new MockHttpServletResponse();
    controller.serve(
        PublishedResource.Visibility.PUBLIC,
        USER_ID,
        TOKEN,
        requestFor("public", "report.txt"),
        response);

    assertThat(response.getStatus()).isEqualTo(410);
  }

  @Test
  @DisplayName("returns 404 when the token does not exist")
  void returns404WhenNotFound() {
    when(publishedResourceRepo.findById(any())).thenReturn(Optional.empty());

    final var response = new MockHttpServletResponse();
    controller.serve(
        PublishedResource.Visibility.PUBLIC,
        USER_ID,
        TOKEN,
        requestFor("public", "report.txt"),
        response);

    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("returns 404 when the requested visibility does not match the stored record")
  void returns404OnVisibilityMismatch() throws IOException {
    final var resource =
        PublishedResource.builder()
            .id(TOKEN)
            .ownerId(USER_ID)
            .visibility(PublishedResource.Visibility.INTERNAL)
            .directory(false)
            .entryFilename("report.txt")
            .build();
    seed(resource, "report.txt", "secret");

    final var response = new MockHttpServletResponse();
    controller.serve(
        PublishedResource.Visibility.PUBLIC,
        USER_ID,
        TOKEN,
        requestFor("public", "report.txt"),
        response);

    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("returns 404 when the userId path segment does not match the resource owner")
  void returns404OnOwnerMismatch() throws IOException {
    final var resource =
        PublishedResource.builder()
            .id(TOKEN)
            .ownerId(USER_ID)
            .visibility(PublishedResource.Visibility.PUBLIC)
            .directory(false)
            .entryFilename("report.txt")
            .build();
    seed(resource, "report.txt", "secret");

    final var response = new MockHttpServletResponse();
    controller.serve(
        PublishedResource.Visibility.PUBLIC,
        "someone-else",
        TOKEN,
        requestFor("public", "report.txt"),
        response);

    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("directory resource defaults to entryFilename when the sub-path is empty")
  void directoryDefaultsToEntryFilename() throws IOException {
    final var resource =
        PublishedResource.builder()
            .id(TOKEN)
            .ownerId(USER_ID)
            .visibility(PublishedResource.Visibility.PUBLIC)
            .directory(true)
            .entryFilename("index.html")
            .build();
    seed(resource, "index.html", "<html>home</html>");
    seed(resource, "assets/app.css", "body{}");

    final var response = new MockHttpServletResponse();
    final var body =
        controller.serve(
            PublishedResource.Visibility.PUBLIC,
            USER_ID,
            TOKEN,
            requestFor("public", ""),
            response);

    assertThat(bodyOf(body)).isEqualTo("<html>home</html>");
  }

  @Test
  @DisplayName("directory resource serves nested sub-paths")
  void directoryServesNestedSubPath() throws IOException {
    final var resource =
        PublishedResource.builder()
            .id(TOKEN)
            .ownerId(USER_ID)
            .visibility(PublishedResource.Visibility.PUBLIC)
            .directory(true)
            .entryFilename("index.html")
            .build();
    seed(resource, "index.html", "<html>home</html>");
    seed(resource, "assets/app.css", "body{color:red}");

    final var response = new MockHttpServletResponse();
    final var body =
        controller.serve(
            PublishedResource.Visibility.PUBLIC,
            USER_ID,
            TOKEN,
            requestFor("public", "assets/app.css"),
            response);

    assertThat(bodyOf(body)).isEqualTo("body{color:red}");
  }

  @Test
  @DisplayName("directory resource with no entryFilename 404s on the root path")
  void directoryWithoutEntryFilename404sOnRoot() {
    final var resource =
        PublishedResource.builder()
            .id(TOKEN)
            .ownerId(USER_ID)
            .visibility(PublishedResource.Visibility.PUBLIC)
            .directory(true)
            .entryFilename(null)
            .build();
    when(publishedResourceRepo.findById(TOKEN)).thenReturn(Optional.of(resource));

    final var response = new MockHttpServletResponse();
    controller.serve(
        PublishedResource.Visibility.PUBLIC, USER_ID, TOKEN, requestFor("public", ""), response);

    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("path traversal in the sub-path cannot escape the storage root")
  void rejectsPathTraversalInSubPath() throws IOException {
    final var secretOutsideStorage = storageRoot.resolveSibling("secret-outside.txt");
    Files.writeString(secretOutsideStorage, "top secret");

    final var resource =
        PublishedResource.builder()
            .id(TOKEN)
            .ownerId(USER_ID)
            .visibility(PublishedResource.Visibility.PUBLIC)
            .directory(true)
            .entryFilename("index.html")
            .build();
    seed(resource, "index.html", "<html></html>");

    final var traversalSubPath =
        "../../../../../../../../" + secretOutsideStorage.getFileName().toString();
    final var response = new MockHttpServletResponse();
    final var body =
        controller.serve(
            PublishedResource.Visibility.PUBLIC,
            USER_ID,
            TOKEN,
            requestFor("public", traversalSubPath),
            response);

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(bodyOf(body)).isEmpty();
  }

  @Test
  @DisplayName("an unresolvable storage key (mismatched case) 404s rather than throwing")
  void handlesUnresolvableStorageKeyGracefully() throws IOException {
    // Seeded under lowercase "public", but the resource's entryFilename points at a file that
    // was never actually stored.
    final var resource =
        PublishedResource.builder()
            .id(TOKEN)
            .ownerId(USER_ID)
            .visibility(PublishedResource.Visibility.PUBLIC)
            .directory(false)
            .entryFilename("missing.txt")
            .build();
    when(publishedResourceRepo.findById(TOKEN)).thenReturn(Optional.of(resource));

    final var response = new MockHttpServletResponse();
    controller.serve(
        PublishedResource.Visibility.PUBLIC,
        USER_ID,
        TOKEN,
        requestFor("public", "missing.txt"),
        response);

    assertThat(response.getStatus()).isEqualTo(404);
  }
}
