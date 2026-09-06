package me.kezhenxu94.springagent.core.tools;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@AgentTool
@Component
@RequiredArgsConstructor
public class SkillManagementTools {
  private final UserWorkspaceFactory userWorkspaceFactory;

  /** What this hands back to the model, in the workspace's language. */
  private final CoreMessages messages;

  private String validatePath(final String path, final HomeDir home) {
    final var resolved = Path.of(path).toAbsolutePath().normalize();
    if (home.containsIn(HomeDir.Folder.SKILLS, resolved)) return null;
    return messages.get("skill-access-denied");
  }

  @Tool(
      name = "ListSkills",
      description =
"""
List all available skills across all skill directories.

A skill is a folder containing a SKILL.md file. Returns the folder path and skill name for each installed skill.

Usage:
- Call with no arguments.
- Returns a list of skill folder paths and their names.
- Skills from the current user's personal skills directory, the current group's shared skills
  directory (when the request has one), and the tenant's company-wide shared skills directory
  (when the request has one) are all included.
""")
  public String listSkills(final ToolContext context) {
    final List<Path> skillsDirs;
    try {
      skillsDirs = userWorkspaceFactory.forRequest(context).dirs(HomeDir.Folder.SKILLS);
    } catch (IOException e) {
      return messages.get("skill-no-directory", e.getMessage());
    }

    final var result = new StringBuilder();
    int total = 0;
    for (final var skillsDir : skillsDirs) {
      final var root = skillsDir.toFile();
      final var subDirs =
          root.exists() && root.isDirectory() ? root.listFiles(File::isDirectory) : null;
      if (subDirs != null) {
        for (final var skillDir : subDirs) {
          final var skillMd = new File(skillDir, "SKILL.md");
          if (skillMd.exists()) {
            result.append(skillDir.getAbsolutePath()).append("\n");
            total++;
          }
        }
      }
    }
    if (total == 0) return messages.get("skill-none");
    return messages.get("skill-found", total, result);
  }

  @Tool(
      name = "WriteSkillFile",
      description =
"""
Creates or overwrites a file inside a skill folder.

Usage:
- file_path must be an absolute path inside a skill folder.
- Parent directories are created automatically.
- When creating a new skill, write SKILL.md first. It must contain at minimum:
    ---
    name: <skill-name>
    description: <one-line description of what it does and when to use it>
    ---
    <instructions>
- Additional files (scripts, references, etc.) can be written alongside SKILL.md.
- Write into the user's own skills directory. A group's or the tenant's shared one is for a skill
  the user has asked to share: everyone who shares that directory will have the skill loaded into
  their own conversations, and nobody reviews it on the way in.
""")
  public String writeSkillFile(
      @ToolParam(description = "Absolute path to the file inside a skill folder") String filePath,
      @ToolParam(description = "Content to write") String content,
      final ToolContext context) {

    final var home = userWorkspaceFactory.forRequest(context);

    final var accessError = validatePath(filePath, home);
    if (accessError != null) return accessError;

    final var file = new File(filePath);
    final var parent = file.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      return messages.get("skill-no-parent-dirs", filePath);
    }

    final boolean existed = file.exists();
    try (final var writer = new BufferedWriter(new FileWriter(file, false))) {
      writer.write(content != null ? content : "");
    } catch (IOException e) {
      return messages.get("skill-write-failed", e.getMessage());
    }

    return messages.get(
        existed ? "skill-file-overwritten" : "skill-file-created", filePath, content.length());
  }

  @Tool(
      name = "DeleteSkill",
      description =
"""
Deletes an entire skill folder and all its contents.

Usage:
- skillFolderPath must be an absolute path to a skill folder (a directory containing SKILL.md).
- This operation is irreversible.
""")
  public String deleteSkill(
      @ToolParam(description = "Absolute path to the skill folder to delete")
          String skillFolderPath,
      final ToolContext context) {

    final var home = userWorkspaceFactory.forRequest(context);

    final var accessError = validatePath(skillFolderPath, home);
    if (accessError != null) return accessError;

    final var dir = new File(skillFolderPath);
    if (!dir.exists()) return messages.get("skill-folder-missing", skillFolderPath);
    if (!dir.isDirectory()) return messages.get("skill-not-a-directory", skillFolderPath);
    if (!new File(dir, "SKILL.md").exists()) {
      return messages.get("skill-not-a-skill", skillFolderPath);
    }

    try {
      Files.walk(Path.of(skillFolderPath))
          .sorted(Comparator.reverseOrder())
          .map(Path::toFile)
          .forEach(File::delete);
    } catch (IOException e) {
      return messages.get("skill-delete-failed", e.getMessage());
    }

    return messages.get("skill-deleted", skillFolderPath);
  }

  @Tool(
      name = "DeleteSkillFile",
      description =
"""
Deletes a single file inside a skill folder.

Usage:
- file_path must be an absolute path to a file inside a skill folder.
- To delete an entire skill, use DeleteSkill instead.
""")
  public String deleteSkillFile(
      @ToolParam(description = "Absolute path to the file inside a skill folder") String filePath,
      final ToolContext context) {

    final var home = userWorkspaceFactory.forRequest(context);

    final var accessError = validatePath(filePath, home);
    if (accessError != null) return accessError;

    final var file = new File(filePath);
    if (!file.exists()) return messages.get("skill-file-missing", filePath);
    if (file.isDirectory()) return messages.get("skill-file-is-directory", filePath);

    if (!file.delete()) return messages.get("skill-file-delete-failed", filePath);
    return messages.get("skill-file-deleted", filePath);
  }
}
