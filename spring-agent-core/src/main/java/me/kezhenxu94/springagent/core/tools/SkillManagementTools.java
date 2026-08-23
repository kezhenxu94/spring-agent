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
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@AgentTool
@Component
@RequiredArgsConstructor
public class SkillManagementTools {
  private final UserWorkspaceFactory userWorkspaceFactory;

  private String validatePath(String path, HomeDir home) {
    final var resolved = Path.of(path).toAbsolutePath().normalize();
    if (home.containsIn(HomeDir.Folder.SKILLS, resolved)) return null;
    return "Error: Access denied. Path is outside the allowed skill directories.";
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
      return "Error: failed to resolve skills directory: " + e.getMessage();
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
    if (total == 0) return "No skills installed.";
    return String.format("Found %d skill(s):\n\n%s", total, result);
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
    description: <one-line description>
    ---
    <instructions>
- Additional files (scripts, references, etc.) can be written alongside SKILL.md.
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
      return "Error: Failed to create parent directories for: " + filePath;
    }

    final boolean existed = file.exists();
    try (final var writer = new BufferedWriter(new FileWriter(file, false))) {
      writer.write(content != null ? content : "");
    } catch (IOException e) {
      return "Error writing file: " + e.getMessage();
    }

    return existed
        ? String.format("Successfully overwrote file: %s (%d bytes)", filePath, content.length())
        : String.format("Successfully created file: %s (%d bytes)", filePath, content.length());
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
    if (!dir.exists()) return "Error: Skill folder does not exist: " + skillFolderPath;
    if (!dir.isDirectory()) return "Error: Path is not a directory: " + skillFolderPath;
    if (!new File(dir, "SKILL.md").exists()) {
      return "Error: Not a skill folder (no SKILL.md found): " + skillFolderPath;
    }

    try {
      Files.walk(Path.of(skillFolderPath))
          .sorted(Comparator.reverseOrder())
          .map(Path::toFile)
          .forEach(File::delete);
    } catch (IOException e) {
      return "Error deleting skill: " + e.getMessage();
    }

    return "Successfully deleted skill: " + skillFolderPath;
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
    if (!file.exists()) return "Error: File does not exist: " + filePath;
    if (file.isDirectory())
      return "Error: Path is a directory; use DeleteSkill instead: " + filePath;

    if (!file.delete()) return "Error: Failed to delete file: " + filePath;
    return "Successfully deleted file: " + filePath;
  }
}
