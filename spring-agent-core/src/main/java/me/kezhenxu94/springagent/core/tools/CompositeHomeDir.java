package me.kezhenxu94.springagent.core.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Several {@link HomeDir}s (e.g. a user's personal, group, and tenant homes) treated as one.
 *
 * <p>Reads span every member, writes go to the primary one. The primary is the home of whoever made
 * the request: what a run produces belongs to the person who asked for it, and putting it where
 * others can see it should stay a deliberate act rather than something a tool does by picking the
 * wrong directory.
 */
public final class CompositeHomeDir implements HomeDir {
  private final HomeDir primary;

  /** The primary first, then the shared scopes, which is the order every read answers in. */
  private final List<HomeDir> members;

  public CompositeHomeDir(HomeDir primary, List<HomeDir> shared) {
    this.primary = primary;
    final var all = new ArrayList<HomeDir>();
    all.add(primary);
    all.addAll(shared);
    this.members = List.copyOf(all);
  }

  @Override
  public Path root() {
    return primary.root();
  }

  @Override
  public Path folder(Folder folder) throws IOException {
    return primary.folder(folder);
  }

  @Override
  public List<Path> roots() {
    final var result = new ArrayList<Path>();
    for (final var member : members) result.addAll(member.roots());
    return List.copyOf(result);
  }

  @Override
  public List<Path> dirs(Folder folder) throws IOException {
    final var result = new ArrayList<Path>();
    for (final var member : members) result.addAll(member.dirs(folder));
    return List.copyOf(result);
  }

  @Override
  public boolean contains(Path candidate) {
    return members.stream().anyMatch(m -> m.contains(candidate));
  }
}
