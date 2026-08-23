package me.kezhenxu94.springagent.core.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** One scope's home directory — a personal, a group's, or a tenant's. */
public class UserHome implements HomeDir {

  private final Path root;

  public UserHome(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  @Override
  public Path root() {
    return root;
  }

  @Override
  public Path folder(Folder folder) throws IOException {
    return Files.createDirectories(root.resolve(folder.dirName()));
  }

  @Override
  public List<Path> roots() {
    return List.of(root);
  }

  @Override
  public List<Path> dirs(Folder folder) {
    final var dir = root.resolve(folder.dirName());
    return Files.isDirectory(dir) ? List.of(dir) : List.of();
  }

  @Override
  public boolean contains(Path candidate) {
    return candidate.toAbsolutePath().normalize().startsWith(root);
  }
}
