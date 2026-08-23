package me.kezhenxu94.springagent.core.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * A home directory, whether that's exactly one scope's home ({@link UserHome} — personal, group, or
 * tenant) or several composed together ({@link CompositeHomeDir}).
 *
 * <p>Every folder is reachable two ways, because reading and writing want different answers when
 * more than one scope is in play. {@link #folder(Folder)} and its named shorthands answer with the
 * single directory new content goes into — for a composite, its primary member, which is the home
 * of whoever made the request. {@link #dirs(Folder)} answers with every scope's copy of that
 * folder, which is what a read spans.
 */
public interface HomeDir {

  @Getter
  @RequiredArgsConstructor
  @Accessors(fluent = true)
  enum Folder {
    MEMORIES("memories"),
    ARTIFACTS("artifacts"),
    SKILLS("skills"),
    WORKSPACE("workspace");

    private final String dirName;
  }

  /** Where new content goes: the one home, or a composite's primary member. */
  Path root();

  /** The directory new content of this kind goes into, created if it isn't there yet. */
  Path folder(Folder folder) throws IOException;

  /** Every scope's root, the one written to first. */
  List<Path> roots();

  /**
   * Every scope's copy of {@code folder} that exists, the one written to first.
   *
   * <p>Unlike {@link #folder(Folder)} this creates nothing: listing skills or checking where a file
   * lives must not materialise a directory in a group's or a tenant's shared storage on behalf of
   * one user who happened to read. Creating is the write path's job.
   */
  List<Path> dirs(Folder folder) throws IOException;

  /** Whether {@code candidate} lies inside any scope's home. */
  boolean contains(Path candidate);

  /**
   * Whether {@code candidate} lies inside any scope's {@code folder}, and nowhere else.
   *
   * <p>Asks where the folder would be rather than whether it is there yet: writing the first skill
   * a group ever gets is a legitimate thing to allow, and the directory it goes in comes into
   * existence as part of that write.
   */
  default boolean containsIn(Folder folder, Path candidate) {
    final var resolved = candidate.toAbsolutePath().normalize();
    return roots().stream().anyMatch(root -> resolved.startsWith(root.resolve(folder.dirName())));
  }

  default Path memories() throws IOException {
    return folder(Folder.MEMORIES);
  }

  default Path artifacts() throws IOException {
    return folder(Folder.ARTIFACTS);
  }

  default Path skills() throws IOException {
    return folder(Folder.SKILLS);
  }

  default Path workspace() throws IOException {
    return folder(Folder.WORKSPACE);
  }
}
