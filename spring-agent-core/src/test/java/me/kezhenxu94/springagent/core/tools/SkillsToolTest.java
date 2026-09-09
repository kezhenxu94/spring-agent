package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * That a skill is a tool of its own, and that a skill which cannot be one costs only itself.
 *
 * <p>The second half is what this repository added to <a
 * href="https://github.com/spring-ai-community/spring-ai-agent-utils/pull/73">upstream PR #73</a>,
 * and it is the half worth keeping a test on: upstream fails the build for a skill it cannot
 * register, and here that would fail the composition of every run belonging to whoever owns the
 * skill — including the run that would have renamed it.
 */
class SkillsToolTest {

  private static Path writeSkill(final Path parent, final String directory, final String skillMd)
      throws IOException {
    final var skillDir = parent.resolve(directory);
    Files.createDirectories(skillDir);
    Files.writeString(skillDir.resolve("SKILL.md"), skillMd);
    return skillDir;
  }

  private static List<String> toolNames(final ToolCallbackProvider provider) {
    return Arrays.stream(provider.getToolCallbacks())
        .map(callback -> callback.getToolDefinition().name())
        .toList();
  }

  private static ToolCallback toolNamed(final ToolCallbackProvider provider, final String name) {
    return Arrays.stream(provider.getToolCallbacks())
        .filter(callback -> name.equals(callback.getToolDefinition().name()))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("No tool named " + name + " in " + toolNames(provider)));
  }

  @Test
  @DisplayName("one tool per skill, each named after the skill and described by its front matter")
  void oneToolPerSkill(@TempDir final Path skills) throws IOException {
    writeSkill(
        skills,
        "one",
        """
        ---
        name: fill-a-pdf
        description: fills in a PDF form
        ---
        Open the form.
        """);
    writeSkill(
        skills,
        "two",
        """
        ---
        name: read-a-sheet
        description: reads a spreadsheet
        ---
        Open the sheet.
        """);

    final var provider = SkillsTool.builder().addSkillsDirectory(skills.toString()).build();

    assertThat(toolNames(provider))
        .containsExactlyInAnyOrder("skill_fill-a-pdf", "skill_read-a-sheet");
    assertThat(toolNamed(provider, "skill_fill-a-pdf").getToolDefinition().description())
        .contains("fills in a PDF form")
        .doesNotContain("reads a spreadsheet");
  }

  @Test
  @DisplayName("the answer opens with the skill's directory, and the tool takes no arguments")
  void answersWithTheBaseDirectory(@TempDir final Path skills) throws IOException {
    final var dir =
        writeSkill(
            skills,
            "one",
            """
            ---
            name: fill-a-pdf
            description: fills in a PDF form
            ---
            Open the form.
            """);

    final var provider = SkillsTool.builder().addSkillsDirectory(skills.toString()).build();
    final var tool = toolNamed(provider, "skill_fill-a-pdf");

    assertThat(tool.getToolDefinition().inputSchema())
        .as("a skill takes no arguments: the tool called is the choice of skill")
        .contains("\"properties\" : { }");
    assertThat(tool.call("{}"))
        .contains("Base directory for this skill: " + dir)
        .contains("Open the form.");
  }

  @Test
  @DisplayName("extra front-matter fields reach the description, since nothing else carries them")
  void extraFrontMatterFields(@TempDir final Path skills) throws IOException {
    writeSkill(
        skills,
        "one",
        """
        ---
        name: fill-a-pdf
        description: fills in a PDF form
        allowed-tools: Read, Bash
        ---
        Open the form.
        """);

    final var provider = SkillsTool.builder().addSkillsDirectory(skills.toString()).build();

    assertThat(toolNamed(provider, "skill_fill-a-pdf").getToolDefinition().description())
        .contains("fills in a PDF form")
        .contains("allowed-tools: Read, Bash");
  }

  @Test
  @DisplayName("a scoped skill name keeps its name, and the tool name it derives is legal")
  void scopedSkillNames(@TempDir final Path skills) throws IOException {
    writeSkill(
        skills,
        "a",
        """
        ---
        name: project-a:pdf
        description: project A's PDF skill
        ---
        A.
        """);
    writeSkill(
        skills,
        "b",
        """
        ---
        name: project-b:pdf
        description: project B's PDF skill
        ---
        B.
        """);

    final var provider = SkillsTool.builder().addSkillsDirectory(skills.toString()).build();

    assertThat(toolNames(provider))
        .containsExactlyInAnyOrder("skill_project-a_pdf", "skill_project-b_pdf");
    assertThat(toolNamed(provider, "skill_project-a_pdf").call("{}")).contains("A.");
  }

  @Test
  @DisplayName(
      "the template is the seam a deployment translates, one skill's front matter at a time")
  void customTemplate(@TempDir final Path skills) throws IOException {
    writeSkill(
        skills,
        "one",
        """
        ---
        name: fill-a-pdf
        description: fills in a PDF form
        ---
        Open the form.
        """);

    final var provider =
        SkillsTool.builder()
            .addSkillsDirectory(skills.toString())
            .toolDescriptionTemplate("Skill: %s")
            .build();

    assertThat(toolNamed(provider, "skill_fill-a-pdf").getToolDefinition().description())
        .isEqualTo("Skill: fills in a PDF form");
  }

  @Test
  @DisplayName("of two skills of one name the first is offered, so the nearest scope wins")
  void duplicateNamesKeepTheFirst(@TempDir final Path homes) throws IOException {
    final var near = homes.resolve("mine/skills");
    final var far = homes.resolve("the-tenant/skills");
    writeSkill(
        near,
        "mine",
        """
        ---
        name: fill-a-pdf
        description: mine
        ---
        Mine.
        """);
    writeSkill(
        far,
        "theirs",
        """
        ---
        name: fill-a-pdf
        description: the tenant's
        ---
        Theirs.
        """);

    final var provider =
        SkillsTool.builder().addSkillsDirectories(List.of(near.toString(), far.toString())).build();

    assertThat(toolNames(provider)).containsExactly("skill_fill-a-pdf");
    assertThat(toolNamed(provider, "skill_fill-a-pdf").call("{}")).contains("Mine.");
  }

  @Test
  @DisplayName("a skill that cannot be a tool is left out, and the others still are one")
  // The over-long name is 59 characters, which is legal for a skill and one too many for a tool
  // once the prefix is counted — the case a test on the bare name would have missed.
  void unusableSkillsAreSkipped(@TempDir final Path skills) throws IOException {
    writeSkill(
        skills,
        "nameless",
        """
        ---
        description: a skill whose front matter forgot to name it
        ---
        Nothing can call this.
        """);
    writeSkill(
        skills,
        "long",
        """
        ---
        name: %s
        description: a skill named past what any provider accepts once prefixed
        ---
        Nothing can call this either.
        """
            .formatted("x".repeat(59)));
    writeSkill(
        skills,
        "fine",
        """
        ---
        name: fill-a-pdf
        description: fills in a PDF form
        ---
        Open the form.
        """);

    final var provider = SkillsTool.builder().addSkillsDirectory(skills.toString()).build();

    assertThat(toolNames(provider))
        .as("the run keeps the skills it can offer rather than losing all of them")
        .containsExactly("skill_fill-a-pdf");
  }

  @Test
  @DisplayName("being given no skills at all is the one thing build() refuses")
  void noSkillsAtAll(@TempDir final Path empty) {
    assertThatThrownBy(() -> SkillsTool.builder().addSkillsDirectory(empty.toString()).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At least one skill must be configured");
  }
}
