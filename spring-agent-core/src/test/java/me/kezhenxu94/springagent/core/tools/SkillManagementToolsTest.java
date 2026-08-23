package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    tools = new SkillManagementTools(userWorkspaceFactory);
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
