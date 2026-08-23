package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The two {@link HomeDir} implementations and the factory that composes them. */
class HomeDirTest {

  @Nested
  class Leaf {
    @TempDir Path root;

    @Test
    @DisplayName("folder() creates the directory it hands back, dirs() creates nothing")
    void folderCreatesDirsDoesNot() throws Exception {
      final var home = new UserHome(root);

      assertThat(home.dirs(HomeDir.Folder.SKILLS)).isEmpty();
      assertThat(root.resolve("skills")).doesNotExist();

      assertThat(home.skills()).isEqualTo(root.resolve("skills")).exists();
      assertThat(home.dirs(HomeDir.Folder.SKILLS)).containsExactly(root.resolve("skills"));
    }

    @Test
    @DisplayName("containsIn() is narrower than contains(): a sibling folder is not in scope")
    void containsInIsNarrowerThanContains() throws Exception {
      final var home = new UserHome(root);
      final var memory = home.memories().resolve("MEMORY.md");

      assertThat(home.contains(memory)).isTrue();
      assertThat(home.containsIn(HomeDir.Folder.SKILLS, memory)).isFalse();
      // Not yet created: where the folder belongs is the question, not whether it is there.
      assertThat(home.containsIn(HomeDir.Folder.SKILLS, root.resolve("skills/a/SKILL.md")))
          .isTrue();
      assertThat(root.resolve("skills")).doesNotExist();
    }

    @Test
    @DisplayName("a path outside the root is in no scope at all")
    void outsideTheRoot(@TempDir final Path elsewhere) {
      assertThat(new UserHome(root).contains(elsewhere.resolve("x.txt"))).isFalse();
    }

    @Test
    @DisplayName(
        "a configured workspace root replaces root/workspace, other folders are unaffected")
    void separateWorkspaceRoot(@TempDir final Path workspaceRoot) throws Exception {
      final var home = new UserHome(root, workspaceRoot);

      assertThat(home.workspace()).isEqualTo(workspaceRoot).exists();
      assertThat(root.resolve("workspace")).doesNotExist();
      assertThat(home.skills()).isEqualTo(root.resolve("skills")).exists();
    }

    @Test
    @DisplayName(
        "a separate workspace root is included in roots() and contains(), but only for workspace")
    void separateWorkspaceRootIsInScope(@TempDir final Path workspaceRoot) {
      final var home = new UserHome(root, workspaceRoot);
      final var file = workspaceRoot.resolve("script.py");

      assertThat(home.roots()).containsExactly(root, workspaceRoot);
      assertThat(home.contains(file)).isTrue();
      assertThat(home.containsIn(HomeDir.Folder.WORKSPACE, file)).isTrue();
      assertThat(home.containsIn(HomeDir.Folder.SKILLS, file)).isFalse();
    }
  }

  @Nested
  class Composite {
    @TempDir Path personal;
    @TempDir Path group;

    @Test
    @DisplayName("writes go to the primary member, and only to it")
    void writesGoToThePrimary() throws Exception {
      final var home = new CompositeHomeDir(new UserHome(personal), List.of(new UserHome(group)));

      assertThat(home.root()).isEqualTo(personal);
      assertThat(home.artifacts()).isEqualTo(personal.resolve("artifacts")).exists();
      assertThat(group.resolve("artifacts")).doesNotExist();
    }

    @Test
    @DisplayName("reads span every member that has the folder, the primary first")
    void readsSpanEveryMember() throws Exception {
      final var home = new CompositeHomeDir(new UserHome(personal), List.of(new UserHome(group)));

      assertThat(home.roots()).containsExactly(personal, group);
      assertThat(home.dirs(HomeDir.Folder.SKILLS)).isEmpty();

      Files.createDirectories(group.resolve("skills"));
      assertThat(home.dirs(HomeDir.Folder.SKILLS)).containsExactly(group.resolve("skills"));

      home.skills();
      assertThat(home.dirs(HomeDir.Folder.SKILLS))
          .containsExactly(personal.resolve("skills"), group.resolve("skills"));
    }

    @Test
    @DisplayName("containment is any member's, and still narrows to one kind of folder")
    void containment() throws Exception {
      final var home = new CompositeHomeDir(new UserHome(personal), List.of(new UserHome(group)));
      final var groupSkill = group.resolve("skills/shared");
      final var groupMemory = group.resolve("memories");

      assertThat(home.contains(groupSkill)).isTrue();
      assertThat(home.containsIn(HomeDir.Folder.SKILLS, groupSkill.resolve("SKILL.md"))).isTrue();
      assertThat(home.containsIn(HomeDir.Folder.SKILLS, groupMemory.resolve("MEMORY.md")))
          .isFalse();
    }
  }

  @Nested
  class Factory {
    @TempDir Path location;

    private UserWorkspaceFactory factory() {
      return new UserWorkspaceFactory(
          FileSystemStorageProperties.builder().location(location.toString()).build());
    }

    @Test
    @DisplayName("each scope has its own namespace under the storage location")
    void namespaces() {
      final var factory = factory();

      assertThat(factory.forOwner("ou_1").root()).isEqualTo(location.resolve("ou_1"));
      assertThat(factory.forGroup("oc_1").root()).isEqualTo(location.resolve("groups/oc_1"));
      assertThat(factory.forTenant("t_1").root()).isEqualTo(location.resolve("tenant/t_1"));
    }

    @Test
    @DisplayName("a request with no shared scope is the owner's home itself, not a composite")
    void noSharedScope() {
      assertThat(factory().forRequest("ou_1", null, "  ")).isInstanceOf(UserHome.class);
    }

    @Test
    @DisplayName("a request composes the owner's home with whichever shared scopes it has")
    void sharedScopes() {
      final var factory = factory();

      assertThat(factory.forRequest("ou_1", "oc_1", null).roots())
          .containsExactly(location.resolve("ou_1"), location.resolve("groups/oc_1"));
      assertThat(factory.forRequest("ou_1", null, "t_1").roots())
          .containsExactly(location.resolve("ou_1"), location.resolve("tenant/t_1"));
      assertThat(factory.forRequest("ou_1", "oc_1", "t_1").roots())
          .containsExactly(
              location.resolve("ou_1"),
              location.resolve("groups/oc_1"),
              location.resolve("tenant/t_1"));
    }
  }

  @Nested
  class FactoryWithSeparateWorkspaceLocation {
    @TempDir Path location;
    @TempDir Path workspaceLocation;

    private UserWorkspaceFactory factory() {
      return new UserWorkspaceFactory(
          FileSystemStorageProperties.builder()
              .location(location.toString())
              .workspaceLocation(workspaceLocation.toString())
              .build());
    }

    @Test
    @DisplayName(
        "each scope's workspace lives under workspace-location instead of nested under its home")
    void workspaceNamespacedSeparately() throws Exception {
      final var factory = factory();

      assertThat(factory.forOwner("ou_1").workspace()).isEqualTo(workspaceLocation.resolve("ou_1"));
      assertThat(factory.forGroup("oc_1").workspace())
          .isEqualTo(workspaceLocation.resolve("groups/oc_1"));
      assertThat(factory.forTenant("t_1").workspace())
          .isEqualTo(workspaceLocation.resolve("tenant/t_1"));
      assertThat(location.resolve("ou_1/workspace")).doesNotExist();
    }
  }
}
