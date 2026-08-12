package me.kezhenxu94.springagent.core.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

public class UserHome {

  @Getter
  @RequiredArgsConstructor
  @Accessors(fluent = true)
  public enum Folder {
    MEMORIES("memories"),
    ARTIFACTS("artifacts"),
    SKILLS("skills"),
    WORKSPACE("workspace");

    private final String dirName;
  }

  private final Path root;

  public UserHome(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public Path root() {
    return root;
  }

  public Path folder(Folder folder) throws IOException {
    return Files.createDirectories(root.resolve(folder.dirName()));
  }

  public Path memories() throws IOException {
    return folder(Folder.MEMORIES);
  }

  public Path artifacts() throws IOException {
    return folder(Folder.ARTIFACTS);
  }

  public Path skills() throws IOException {
    return folder(Folder.SKILLS);
  }

  public Path workspace() throws IOException {
    return folder(Folder.WORKSPACE);
  }

  public boolean contains(Path candidate) {
    return candidate.toAbsolutePath().normalize().startsWith(root);
  }
}
