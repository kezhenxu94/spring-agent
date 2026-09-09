package me.kezhenxu94.springagent.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeScope.Target;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which memories a request reaches, which it may write, and what it is told about them. */
class MemoryScopesTest {

  @TempDir Path location;

  private UserWorkspaceFactory factory() {
    return new UserWorkspaceFactory(
        FileSystemStorageProperties.builder().location(location.toString()).build());
  }

  private MemoryScopes scopes(
      final boolean admin, final String userId, final String groupId, final String tenantId) {
    return MemoryScopes.forRequest(factory(), admin, userId, groupId, tenantId);
  }

  @Nested
  @DisplayName("where each scope's memories live")
  class Layout {

    @Test
    @DisplayName("a group is namespaced under groups/ and a tenant under tenant/")
    void layout() {
      final var s = scopes(false, "ou_1", "oc_9", "t_3");
      // Asserted rather than left to the factory, because the plural/singular asymmetry is what
      // existing deployments already have on disk: "fixing" it to groups/ and tenants/ would orphan
      // every memory a group or a tenant has written so far.
      assertThat(s.own()).isEqualTo(location.resolve("ou_1").resolve("memories"));
      assertThat(s.group()).isEqualTo(location.resolve("groups/oc_9").resolve("memories"));
      assertThat(s.tenant()).isEqualTo(location.resolve("tenant/t_3").resolve("memories"));
    }

    @Test
    @DisplayName("naming the scopes creates none of their directories")
    void createsNothing() {
      final var s = scopes(false, "ou_1", "oc_9", "t_3");
      s.describe(TestI18n.english());
      assertThat(s.own()).doesNotExist();
      assertThat(s.group()).doesNotExist();
      assertThat(s.tenant()).doesNotExist();
      assertThat(location).isEmptyDirectory();
    }

    @Test
    @DisplayName("a blank group or tenant is a scope the request does not have")
    void blankIsAbsent() {
      assertThat(scopes(false, "ou_1", "", null).readable()).containsExactly(Target.OWN);
      assertThat(scopes(false, "ou_1", null, "t_3").readable())
          .containsExactly(Target.OWN, Target.TENANT);
      assertThat(scopes(false, "ou_1", "oc_9", "t_3").readable())
          .containsExactly(Target.OWN, Target.GROUP, Target.TENANT);
    }
  }

  @Nested
  @DisplayName("what may be written where")
  class WritePolicy {

    @Test
    @DisplayName("a one-to-one chat writes only its own, and reads the tenant")
    void p2p() {
      final var s = scopes(false, "ou_1", null, "t_3");
      assertThat(s.writable(Target.OWN)).isTrue();
      assertThat(s.writable(Target.GROUP)).isFalse();
      assertThat(s.writable(Target.TENANT)).isFalse();
      // Readable but not writable is the whole point: a p2p run consults what the company
      // remembers without being able to add to it.
      assertThat(s.has(Target.TENANT)).isTrue();
    }

    @Test
    @DisplayName("a group chat writes all three")
    void group() {
      final var s = scopes(false, "ou_1", "oc_9", "t_3");
      assertThat(s.writable(Target.OWN)).isTrue();
      assertThat(s.writable(Target.GROUP)).isTrue();
      assertThat(s.writable(Target.TENANT)).isTrue();
    }

    @Test
    @DisplayName("an admin writes the tenant from a one-to-one chat, but there is still no group")
    void admin() {
      final var s = scopes(true, "ou_1", null, "t_3");
      assertThat(s.writable(Target.TENANT)).isTrue();
      assertThat(s.writable(Target.GROUP)).isFalse();
    }

    @Test
    @DisplayName("a deployment with no tenant has no tenant memory to write, admin or not")
    void noTenant() {
      assertThat(scopes(true, "ou_1", "oc_9", null).writable(Target.TENANT)).isFalse();
      assertThat(scopes(true, "ou_1", "oc_9", null).has(Target.TENANT)).isFalse();
    }
  }

  @Nested
  @DisplayName("the block the model is given")
  class Describe {

    @Test
    @DisplayName("one line per reachable scope, saying which may be written to")
    void english() {
      final var block = scopes(false, "ou_1", null, "t_3").describe(TestI18n.english());
      assertThat(block.lines()).hasSize(2);
      assertThat(block)
          .contains("own")
          .contains(location.resolve("ou_1").resolve("memories").toString())
          .contains("you may write here")
          .contains("tenant")
          .contains("read only in this one-to-one chat");
    }

    @Test
    @DisplayName("the prose is translated but the scope words the model passes back are not")
    void chinese() {
      final var block = scopes(false, "ou_1", "oc_9", "t_3").describe(TestI18n.chinese());
      assertThat(block.lines()).hasSize(3);
      assertThat(block).contains("own").contains("group").contains("tenant");
      assertThat(block).contains("你可以往这里写");
      assertThat(block).doesNotContain("you may write here");
    }

    @Test
    @DisplayName("no user id means nothing to describe, so the paragraph is left out")
    void noUser() {
      assertThat(scopes(false, "", null, null).describe(TestI18n.english())).isEmpty();
    }
  }

  @Nested
  @DisplayName("an existing directory changes nothing about the answer")
  class Existing {

    @Test
    @DisplayName("a scope names the same path whether or not anything is written there")
    void sameEitherWay() throws Exception {
      final var before = scopes(false, "ou_1", "oc_9", null).group();
      Files.createDirectories(before);
      assertThat(scopes(false, "ou_1", "oc_9", null).group()).isEqualTo(before);
    }
  }
}
