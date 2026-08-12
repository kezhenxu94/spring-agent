package me.kezhenxu94.springagent.core.storage;

import static java.util.function.Predicate.not;

import com.google.common.base.Strings;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.util.DigestUtils;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

@RequiredArgsConstructor
public class FileSystemStorageService implements StorageService {
  private final StorageProperties properties;

  @Override
  public Path store(MultipartFile file) {
    try {
      if (file.isEmpty()) {
        throw new StorageException("Failed to store empty file.");
      }
      // MultipartFile#getOriginalFilename() is documented as nullable; Guava's getFileExtension
      // (unlike Apache Commons IO's) throws NPE on a null input, so guard it explicitly.
      final var ext =
          com.google.common.io.Files.getFileExtension(
              Strings.nullToEmpty(file.getOriginalFilename()));
      final var filename = DigestUtils.md5DigestAsHex(file.getInputStream()) + "." + ext;
      try (final var inputStream = file.getInputStream()) {
        return store(inputStream, filename);
      }
    } catch (IOException e) {
      throw new StorageException("Failed to store file.", e);
    }
  }

  @Override
  public Path store(InputStream inputStream, String filename) {
    final var destinationFile = resolve(filename);
    try {
      if (!Files.exists(destinationFile)) {
        com.google.common.io.Files.createParentDirs(destinationFile.toFile());
      }
      Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new StorageException("Failed to store file.", e);
    }
    return destinationFile;
  }

  @Override
  public Stream<Path> loadAll() {
    try {
      final var rootLocation = Path.of(properties.getLocation());
      return Files.walk(rootLocation, 1)
          .filter(not(rootLocation::equals))
          .map(rootLocation::relativize);
    } catch (IOException e) {
      throw new StorageException("Failed to read stored files", e);
    }
  }

  @Override
  public Path load(String filename) {
    final var rootLocation = Path.of(properties.getLocation());
    final var path = rootLocation.resolve(filename);

    if (!path.startsWith(rootLocation.toAbsolutePath())
        || path.equals(rootLocation.toAbsolutePath())) {
      throw new StorageException("Cannot read file outside current directory.");
    }

    return path;
  }

  @Override
  public Resource loadAsResource(String filename) {
    try {
      final var file = load(filename);
      final var resource = new UrlResource(file.toUri());
      if (resource.exists() || resource.isReadable()) {
        return resource;
      } else {
        throw new StorageFileNotFoundException("Could not read file: " + filename);
      }
    } catch (MalformedURLException e) {
      throw new StorageFileNotFoundException("Could not read file: " + filename, e);
    }
  }

  @Override
  public void deleteAll() {
    final var rootLocation = Path.of(properties.getLocation());
    FileSystemUtils.deleteRecursively(rootLocation.toFile());
  }

  @Override
  public void init() {
    try {
      final var rootLocation = Path.of(properties.getLocation());
      Files.createDirectories(rootLocation);
    } catch (IOException e) {
      throw new StorageException("Could not initialize storage", e);
    }
  }

  @Override
  public Path resolve(String filename) {
    final var rootLocation = Path.of(properties.getLocation());
    final var destinationFile =
        rootLocation.resolve(Path.of(filename)).normalize().toAbsolutePath();
    if (!destinationFile.startsWith(rootLocation.toAbsolutePath())
        || destinationFile.equals(rootLocation.toAbsolutePath())) {
      throw new StorageException("Cannot store file outside current directory.");
    }
    return destinationFile;
  }
}
