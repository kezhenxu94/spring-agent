package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import me.kezhenxu94.springagent.core.skills.SkillFiles;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.support.TestTenantWrites;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

class SkillManagementToolsTest {

  @TempDir Path location;

  UserWorkspaceFactory userWorkspaceFactory;
  SkillManagementTools tools;

  private static final ToolContext CONTEXT =
      new ToolContext(
          Map.of(
              ToolContexts.KEY_USER_ID, "ou_1",
              ToolContexts.KEY_GROUP_ID, "oc_1"));

  @BeforeEach
  void setUp() {
    userWorkspaceFactory =
        new UserWorkspaceFactory(
            FileSystemStorageProperties.builder().location(location.toString()).build());
    tools =
        new SkillManagementTools(
            userWorkspaceFactory,
            new SkillFiles(),
            TestTenantWrites.adminsOnly(),
            TestI18n.english());
  }

  @Test
  @DisplayName("a skill can be written into the user's own skills directory")
  void writesIntoTheUsersSkillsDirectory() {
    final var target = location.resolve("ou_1/skills/greeting/SKILL.md");

    assertThat(tools.writeSkillFile(target.toString(), "hello", CONTEXT))
        .startsWith("Successfully created file");
    assertThat(target).exists();
  }

  @Test
  @DisplayName("a skill can be written into the group's shared skills directory")
  void writesIntoTheGroupsSkillsDirectory() {
    final var target = location.resolve("groups/oc_1/skills/greeting/SKILL.md");

    assertThat(tools.writeSkillFile(target.toString(), "hello", CONTEXT))
        .startsWith("Successfully created file");
    assertThat(target).exists();
  }

  @Test
  @DisplayName(
      "the skill tools may not reach out of the skills directories into the rest of a home")
  void refusesTheRestOfTheHome() {
    final var memory = location.resolve("ou_1/memories/MEMORY.md");

    assertThat(tools.writeSkillFile(memory.toString(), "overwritten", CONTEXT))
        .startsWith("Error: Access denied.");
    assertThat(memory).doesNotExist();
    assertThat(tools.deleteSkillFile(memory.toString(), CONTEXT))
        .startsWith("Error: Access denied.");
  }

  @Nested
  @DisplayName("the company's skills")
  class CompanySkills {

    private static final ToolContext TENANT_CONTEXT =
        new ToolContext(
            Map.of(
                ToolContexts.KEY_USER_ID, "ou_1",
                ToolContexts.KEY_GROUP_ID, "oc_1",
                ToolContexts.KEY_TENANT_ID, "t_1"));

    private static final ToolContext ADMIN_CONTEXT =
        new ToolContext(
            Map.of(
                ToolContexts.KEY_USER_ID, "ou_admin",
                ToolContexts.KEY_GROUP_ID, "oc_1",
                ToolContexts.KEY_TENANT_ID, "t_1"));

    private SkillManagementTools tools(final boolean open) {
      return new SkillManagementTools(
          userWorkspaceFactory,
          new SkillFiles(),
          open
              ? TestTenantWrites.openToEveryone("ou_admin")
              : TestTenantWrites.adminsOnly("ou_admin"),
          TestI18n.english());
    }

    @Test
    @DisplayName("by default a member may not write one, and nothing is left behind")
    void refusedByDefault() {
      final var target = location.resolve("tenant/t_1/skills/policy/SKILL.md");

      assertThat(tools(false).writeSkillFile(target.toString(), "hello", TENANT_CONTEXT))
          .contains("only its administrators");
      assertThat(target).doesNotExist();
    }

    @Test
    @DisplayName("nor delete one somebody else wrote")
    void deletesRefusedByDefault() throws Exception {
      final var dir = location.resolve("tenant/t_1/skills/policy");
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("SKILL.md"), "theirs");

      assertThat(tools(false).deleteSkill(dir.toString(), TENANT_CONTEXT))
          .contains("only its administrators");
      assertThat(tools(false).deleteSkillFile(dir.resolve("SKILL.md").toString(), TENANT_CONTEXT))
          .contains("only its administrators");
      assertThat(dir.resolve("SKILL.md")).exists();
    }

    @Test
    @DisplayName("a symlink out of a private skill is still a write into the company's")
    void followsLinks() throws Exception {
      // Lexical containment would call this path the caller's own. The sandbox shell runs as the
      // same user in the same volume, so a link like this is something it can be told to create.
      final var company = location.resolve("tenant/t_1/skills");
      Files.createDirectories(company);
      Files.createDirectories(location.resolve("ou_1/skills"));
      Files.createSymbolicLink(location.resolve("ou_1/skills/theirs"), company);

      assertThat(
              tools(false)
                  .writeSkillFile(
                      location.resolve("ou_1/skills/theirs/policy/SKILL.md").toString(),
                      "hello",
                      TENANT_CONTEXT))
          .contains("only its administrators");
      assertThat(company.resolve("policy")).doesNotExist();
    }

    @Test
    @DisplayName("an admin writes one, and so does anybody once the deployment says so")
    void allowed() {
      final var mine = location.resolve("tenant/t_1/skills/a/SKILL.md");
      assertThat(tools(false).writeSkillFile(mine.toString(), "hello", ADMIN_CONTEXT))
          .startsWith("Successfully created file");

      final var theirs = location.resolve("tenant/t_1/skills/b/SKILL.md");
      assertThat(tools(true).writeSkillFile(theirs.toString(), "hello", TENANT_CONTEXT))
          .startsWith("Successfully created file");
    }

    @Test
    @DisplayName("their own and the group's are untouched by the rule")
    void othersAreUntouched() {
      assertThat(
              tools(false)
                  .writeSkillFile(
                      location.resolve("ou_1/skills/mine/SKILL.md").toString(),
                      "hello",
                      TENANT_CONTEXT))
          .startsWith("Successfully created file");
      assertThat(
              tools(false)
                  .writeSkillFile(
                      location.resolve("groups/oc_1/skills/ours/SKILL.md").toString(),
                      "hello",
                      TENANT_CONTEXT))
          .startsWith("Successfully created file");
    }
  }

  @Test
  @DisplayName("skills are listed from every scope the request reaches")
  void listsEveryScope() throws Exception {
    Files.createDirectories(location.resolve("ou_1/skills/personal"));
    Files.writeString(location.resolve("ou_1/skills/personal/SKILL.md"), "mine");
    Files.createDirectories(location.resolve("groups/oc_1/skills/shared"));
    Files.writeString(location.resolve("groups/oc_1/skills/shared/SKILL.md"), "ours");
    Files.createDirectories(location.resolve("tenant/t_1/skills/company"));
    Files.writeString(location.resolve("tenant/t_1/skills/company/SKILL.md"), "theirs");

    assertThat(tools.listSkills(CONTEXT))
        .contains(location.resolve("ou_1/skills/personal").toString())
        .contains(location.resolve("groups/oc_1/skills/shared").toString())
        .doesNotContain(location.resolve("tenant/t_1/skills/company").toString());
  }
}
