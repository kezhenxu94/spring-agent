package me.kezhenxu94.springagent.core.share;

import com.google.common.base.Strings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.PublishedResource;
import me.kezhenxu94.springagent.core.dao.repo.PublishedResourceRepo;
import me.kezhenxu94.springagent.core.storage.StorageException;
import me.kezhenxu94.springagent.core.storage.StorageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ShareController {
  private final StorageService storageService;
  private final PublishedResourceRepo publishedResourceRepo;

  @GetMapping("/share/{visibility}/{userId}/{token}/**")
  public StreamingResponseBody serve(
      @PathVariable final PublishedResource.Visibility visibility,
      @PathVariable final String userId,
      @PathVariable final String token,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var resource = publishedResourceRepo.findById(token).orElse(null);
    if (resource == null
        || resource.visibility() != visibility
        || !resource.ownerId().equals(userId)) {
      log.warn(
          "No such published resource: token={} visibility={} userId={}",
          token,
          visibility,
          userId);
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return $ -> {};
    }

    if (resource.expiresAt() != null && resource.expiresAt().isBefore(Instant.now())) {
      response.setStatus(HttpServletResponse.SC_GONE);
      return $ -> {};
    }

    final var visibilityDir = visibility.name().toLowerCase();
    final var prefix =
        request.getContextPath() + "/share/" + visibilityDir + "/" + userId + "/" + token + "/";
    final var uri = request.getRequestURI();
    final var subPath = uri.startsWith(prefix) ? uri.substring(prefix.length()) : "";

    final String filename;
    if (resource.directory()) {
      if (Strings.isNullOrEmpty(subPath)) {
        if (Strings.isNullOrEmpty(resource.entryFilename())) {
          response.setStatus(HttpServletResponse.SC_NOT_FOUND);
          return $ -> {};
        }
        filename = resource.entryFilename();
      } else {
        filename = subPath;
      }
    } else {
      filename = resource.entryFilename();
    }

    final var storageKey = visibilityDir + "/" + userId + "/" + token + "/" + filename;
    final Path path;
    try {
      path = storageService.resolve(storageKey);
    } catch (StorageException e) {
      log.warn("Rejected storage key outside the storage root: token={} key={}", token, storageKey);
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return $ -> {};
    }
    if (!Files.exists(path) || !Files.isRegularFile(path)) {
      log.warn("Published file missing on disk: token={} path={}", token, path);
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return $ -> {};
    }

    final var type =
        Optional.ofNullable(URLConnection.guessContentTypeFromName(filename))
            .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    response.setContentType(type);
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline");

    return outputStream -> {
      Files.copy(path, outputStream);
      outputStream.flush();
    };
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  void handleInvalidVisibility(final HttpServletResponse response) {
    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
  }
}
