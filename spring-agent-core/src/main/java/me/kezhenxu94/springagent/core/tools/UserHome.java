package me.kezhenxu94.springagent.core.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One scope's home directory — a personal, a group's, or a tenant's.
 *
 * <p>The workspace folder can be pinned to a separate root instead of nesting under {@code root}
 * like every other folder — e.g. a NAS volume mounted at a different path than the general-purpose
 * storage backing memories/skills/artifacts, so a shell sandbox mounting that same NAS volume and
 * this process agree on where a scope's workspace physically is.
 */
public class UserHome implements HomeDir {

  private final Path root;
  private final Path workspaceRoot;

  public UserHome(Path root) {
    this(root, null);
  }

  public UserHome(Path root, Path workspaceRoot) {
    this.root = root.toAbsolutePath().normalize();
    this.workspaceRoot = workspaceRoot == null ? null : workspaceRoot.toAbsolutePath().normalize();
  }

  @Override
  public Path root() {
    return root;
  }

  @Override
  public Path folder(Folder folder) throws IOException {
    return Files.createDirectories(resolve(folder));
  }

  @Override
  public List<Path> roots() {
    return workspaceRoot == null ? List.of(root) : List.of(root, workspaceRoot);
  }

  @Override
  public List<Path> dirs(Folder folder) {
    final var dir = resolve(folder);
    return Files.isDirectory(dir) ? List.of(dir) : List.of();
  }

  @Override
  public boolean contains(Path candidate) {
    final var resolved = candidate.toAbsolutePath().normalize();
    return resolved.startsWith(root)
        || (workspaceRoot != null && resolved.startsWith(workspaceRoot));
  }

  @Override
  public boolean containsIn(Folder folder, Path candidate) {
    return candidate.toAbsolutePath().normalize().startsWith(resolve(folder));
  }

  private Path resolve(Folder folder) {
    return folder == Folder.WORKSPACE && workspaceRoot != null
        ? workspaceRoot
        : root.resolve(folder.dirName());
  }
}
