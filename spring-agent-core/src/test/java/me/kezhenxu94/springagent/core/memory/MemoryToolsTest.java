package me.kezhenxu94.springagent.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The memory tools against real directories, which is the only way the scope rules can be checked:
 * every one of them is a decision about which path a call lands on.
 */
class MemoryToolsTest {

  private static final String ADMIN = "ou_admin";

  @TempDir Path location;

  private MemoryTools tools;
  private UserWorkspaceFactory factory;

  private MemoryTools tools() {
    if (tools == null) {
      final var properties =
          new SpringAgentProperties(
              new SpringAgentProperties.Ai(Set.of(ADMIN), null, null, null, null, null, null, null),
              Locale.ENGLISH,
              null,
              null);
      factory =
          new UserWorkspaceFactory(
              FileSystemStorageProperties.builder().location(location.toString()).build());
      tools = new MemoryTools(factory, new Admins(properties), TestI18n.english());
    }
    return tools;
  }

  /** A p2p request: a user and a tenant, no group. */
  private ToolContext p2p(final String userId) {
    return context(userId, null, "t_3");
  }

  /** A group request: all three. */
  private ToolContext group(final String userId) {
    return context(userId, "oc_9", "t_3");
  }

  private ToolContext context(final String userId, final String groupId, final String tenantId) {
    final var map = new HashMap<String, Object>();
    // Blank rather than absent for what does not apply, exactly as SpringAgent.toolContextFor
    // nullToEmpty's these on the way in — a null value is rejected by ChatClient outright.
    map.put(ToolContexts.KEY_USER_ID, userId);
    map.put(ToolContexts.KEY_CHAT_ID, "oc_1");
    map.put(ToolContexts.KEY_GROUP_ID, groupId == null ? "" : groupId);
    map.put(ToolContexts.KEY_TENANT_ID, tenantId == null ? "" : tenantId);
    return new ToolContext(Map.copyOf(map));
  }

  private Path memories(final String scopeId) {
    return location.resolve(scopeId).resolve("memories");
  }

  @Nested
  @DisplayName("who may write where")
  class WritePolicy {

    @Test
    @DisplayName("a one-to-one chat cannot write a group memory, and is told to use its own")
    void p2pRefusesGroup() {
      final var answer = tools().memoryCreate("a.md", "group", "x", p2p("ou_1"));
      assertThat(answer).contains("not a group chat").contains("your own memory");
      assertThat(memories("groups/oc_9")).doesNotExist();
    }

    @Test
    @DisplayName("a one-to-one chat cannot write the tenant memory it can read")
    void p2pRefusesTenant() {
      final var answer = tools().memoryCreate("a.md", "tenant", "x", p2p("ou_1"));
      assertThat(answer).contains("cannot be written to from a one-to-one chat");
      assertThat(memories("tenant/t_3")).doesNotExist();
    }

    @Test
    @DisplayName("an admin can write the tenant memory from a one-to-one chat")
    void adminWritesTenantInP2p() {
      final var answer = tools().memoryCreate("a.md", "tenant", "x", p2p(ADMIN));
      assertThat(answer).contains("Saved");
      assertThat(memories("tenant/t_3").resolve("a.md")).exists().hasContent("x");
    }

    @Test
    @DisplayName("a group chat writes into the group's own memories directory")
    void groupWrite() {
      assertThat(tools().memoryCreate("a.md", "group", "x", group("ou_1"))).contains("Saved");
      assertThat(memories("groups/oc_9").resolve("a.md")).exists();
      assertThat(memories("ou_1")).doesNotExist();
    }

    @Test
    @DisplayName("a group chat writes the tenant memory too")
    void groupWritesTenant() {
      assertThat(tools().memoryCreate("a.md", "company", "x", group("ou_1"))).contains("Saved");
      assertThat(memories("tenant/t_3").resolve("a.md")).exists();
    }

    @Test
    @DisplayName("no scope means the requester's own")
    void defaultsToOwn() {
      assertThat(tools().memoryCreate("a.md", null, "x", group("ou_1"))).contains("Saved");
      assertThat(memories("ou_1").resolve("a.md")).exists();
      assertThat(memories("groups/oc_9")).doesNotExist();
    }

    @Test
    @DisplayName("a misspelt scope is refused and lands nowhere")
    void misspeltScope() {
      final var answer = tools().memoryCreate("a.md", "compnay", "x", group("ou_1"));
      assertThat(answer).contains("no memory called").contains("compnay");
      // The dangerous reading would be to treat a typo as "own" and quietly keep the write.
      assertThat(memories("ou_1")).doesNotExist();
      assertThat(memories("tenant/t_3")).doesNotExist();
    }

    @Test
    @DisplayName("a deployment with no tenant says so rather than inventing one")
    void noTenant() {
      final var answer = tools().memoryCreate("a.md", "tenant", "x", context("ou_1", "oc_9", null));
      assertThat(answer).contains("no tenant");
    }
  }

  @Nested
  @DisplayName("reading across scopes")
  class Reading {

    @Test
    @DisplayName("a read with no scope spans every memory the request reaches")
    void spansEveryScope() throws Exception {
      write(memories("ou_1"), "MEMORY.md", "- [Mine](mine.md) - own\n");
      write(memories("groups/oc_9"), "MEMORY.md", "- [Ours](ours.md) - group\n");
      write(memories("tenant/t_3"), "MEMORY.md", "- [Theirs](theirs.md) - tenant\n");

      final var answer = tools().memoryView("MEMORY.md", null, null, group("ou_1"));
      assertThat(answer)
          .contains("- [Mine](mine.md)")
          .contains("- [Ours](ours.md)")
          .contains("- [Theirs](theirs.md)");
      // Each section says which memory it came from, or three indexes would arrive as one.
      assertThat(answer).contains("own").contains("group").contains("tenant");
    }

    @Test
    @DisplayName("the scopes that have no such file are named once, not reported as failures")
    void absentScopesNoted() throws Exception {
      write(memories("ou_1"), "MEMORY.md", "- [Mine](mine.md) - own\n");
      final var answer = tools().memoryView("MEMORY.md", null, null, group("ou_1"));
      assertThat(answer).contains("- [Mine](mine.md)").contains("Not present in");
    }

    @Test
    @DisplayName("a read of a scope with nothing in it answers, and creates nothing")
    void emptyScopeCreatesNothing() {
      final var answer = tools().memoryView("MEMORY.md", "tenant", null, p2p("ou_1"));
      assertThat(answer).contains("nothing has been written here yet");
      assertThat(memories("tenant/t_3")).doesNotExist();
    }

    @Test
    @DisplayName("a bare root lists every reachable memory without creating any of them")
    void rootListing() throws Exception {
      write(memories("ou_1"), "mine.md", "x");
      final var answer = tools().memoryView("", null, null, group("ou_1"));
      assertThat(answer).contains("mine.md");
      assertThat(memories("groups/oc_9")).doesNotExist();
      assertThat(memories("tenant/t_3")).doesNotExist();
    }

    @Test
    @DisplayName("a group scope named from a one-to-one chat is refused on a read too")
    void readRefusesAbsentScope() {
      assertThat(tools().memoryView("MEMORY.md", "group", null, p2p("ou_1")))
          .contains("not a group chat");
    }

    @Test
    @DisplayName("a path in none of the memories says so, naming where it looked")
    void nowhere() {
      assertThat(tools().memoryView("gone.md", null, null, group("ou_1")))
          .contains("No memory at gone.md")
          .contains("own, group, tenant");
    }
  }

  @Nested
  @DisplayName("editing an existing file")
  class Mutations {

    @Test
    @DisplayName("a scope-less edit of a file only a shared memory has is refused, naming it")
    void ambiguousScopeRefused() throws Exception {
      write(memories("groups/oc_9"), "MEMORY.md", "- [Ours](ours.md) - group\n");
      // The sequence this exists for: the model has just read the group's index and is appending
      // the line for a memory it wrote there. Landing that in the user's own index would leave a
      // group memory nobody reading the group's index can find.
      final var answer =
          tools().memoryInsert("MEMORY.md", null, 1, "- [New](new.md)", group("ou_1"));
      assertThat(answer).contains("Say which memory you mean").contains("group");
      assertThat(memories("ou_1")).doesNotExist();
    }

    @Test
    @DisplayName("naming the scope makes the same edit go through")
    void namedScopeEdits() throws Exception {
      write(memories("groups/oc_9"), "MEMORY.md", "- [Ours](ours.md) - group\n");
      assertThat(tools().memoryInsert("MEMORY.md", "group", 1, "- [New](new.md)", group("ou_1")))
          .contains("Inserted at line 1");
      assertThat(memories("groups/oc_9").resolve("MEMORY.md"))
          .content()
          .contains("- [New](new.md)");
    }

    @Test
    @DisplayName("a file the requester's own memory has is edited without naming a scope")
    void ownEditsNeedNoScope() throws Exception {
      write(memories("ou_1"), "a.md", "hello world");
      assertThat(tools().memoryStrReplace("a.md", null, "world", "there", group("ou_1")))
          .contains("Edited a.md");
      assertThat(memories("ou_1").resolve("a.md")).hasContent("hello there");
    }

    @Test
    @DisplayName("an ambiguous old_str is refused rather than half-applied")
    void ambiguousOldStr() throws Exception {
      write(memories("ou_1"), "a.md", "x\nx\n");
      assertThat(tools().memoryStrReplace("a.md", null, "x", "y", p2p("ou_1")))
          .contains("appears 2 times");
      assertThat(memories("ou_1").resolve("a.md")).hasContent("x\nx\n");
    }

    @Test
    @DisplayName("a rename stays inside one memory, and says how to widen who can read one")
    void renameWithinScope() throws Exception {
      write(memories("groups/oc_9"), "a.md", "x");
      assertThat(tools().memoryRename("a.md", "b.md", "group", group("ou_1")))
          .contains("Renamed a.md to b.md");
      assertThat(memories("groups/oc_9").resolve("b.md")).exists();
    }
  }

  @Nested
  @DisplayName("deleting")
  class Deleting {

    @Test
    @DisplayName("a directory in a shared memory is not deleted whole")
    void sharedDirectoryRefused() throws Exception {
      write(memories("groups/oc_9").resolve("sub"), "a.md", "x");
      final var answer = tools().memoryDelete("sub", "group", group("ou_1"));
      assertThat(answer).contains("not deleted whole").contains("one at a time");
      assertThat(memories("groups/oc_9").resolve("sub").resolve("a.md")).exists();
    }

    @Test
    @DisplayName("a directory in the requester's own memory is theirs to delete")
    void ownDirectoryDeleted() throws Exception {
      write(memories("ou_1").resolve("sub"), "a.md", "x");
      assertThat(tools().memoryDelete("sub", null, group("ou_1"))).contains("Deleted sub");
      assertThat(memories("ou_1").resolve("sub")).doesNotExist();
    }

    @Test
    @DisplayName("the memory root itself is not deletable")
    void rootRefused() throws Exception {
      write(memories("ou_1"), "a.md", "x");
      assertThat(tools().memoryDelete("/", null, p2p("ou_1"))).contains("cannot be deleted");
      assertThat(memories("ou_1")).exists();
    }
  }

  @Nested
  @DisplayName("staying inside the memory it was given")
  class Sandbox {

    @Test
    @DisplayName("a parent-directory path is refused")
    void traversalRefused() {
      assertThat(tools().memoryCreate("../escaped.md", null, "x", p2p("ou_1")))
          .contains("outside the memory it was meant for");
      assertThat(location.resolve("ou_1").resolve("escaped.md")).doesNotExist();
    }

    @Test
    @DisplayName("an absolute path is refused")
    void absoluteRefused() {
      assertThat(tools().memoryCreate("/etc/passwd", null, "x", p2p("ou_1")))
          .contains("outside the memory it was meant for");
    }

    @Test
    @DisplayName("a symlink out of a shared memory is refused for reading")
    void symlinkReadRefused() throws Exception {
      final var secret = location.resolve("ou_2").resolve("secret.md");
      Files.createDirectories(secret.getParent());
      Files.writeString(secret, "somebody else's");
      final var shared = memories("groups/oc_9");
      Files.createDirectories(shared);
      Files.createSymbolicLink(shared.resolve("link.md"), secret);

      // Upstream normalizes but never resolves a link, so this read would have returned another
      // user's file to everyone in the chat.
      assertThat(tools().memoryView("link.md", "group", null, group("ou_1")))
          .contains("outside the memory it was meant for")
          .doesNotContain("somebody else");
    }

    @Test
    @DisplayName("a symlink out of a memory is refused for writing")
    void symlinkWriteRefused() throws Exception {
      final var outside = location.resolve("outside.md");
      Files.writeString(outside, "original");
      final var own = memories("ou_1");
      Files.createDirectories(own);
      Files.createSymbolicLink(own.resolve("link.md"), outside);

      assertThat(tools().memoryStrReplace("link.md", null, "original", "overwritten", p2p("ou_1")))
          .contains("outside the memory it was meant for");
      assertThat(outside).hasContent("original");
    }
  }

  @Nested
  @DisplayName("bounding what one call returns")
  class Caps {

    @Test
    @DisplayName("a long file comes back trimmed, saying how to page through the rest")
    void trimmed() throws Exception {
      final var body = new StringBuilder();
      for (var i = 0; i < 500; i++) {
        body.append("line ").append(i).append('\n');
      }
      write(memories("tenant/t_3"), "big.md", body.toString());

      final var answer = tools().memoryView("big.md", "tenant", null, group("ou_1"));
      assertThat(answer).contains("trimmed here").contains("viewRange");
      assertThat(answer.lines().count()).isLessThan(500);
    }
  }

  private static void write(final Path dir, final String name, final String content)
      throws Exception {
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(name), content);
  }
}
