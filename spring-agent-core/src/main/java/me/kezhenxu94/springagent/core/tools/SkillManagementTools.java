package me.kezhenxu94.springagent.core.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.skills.SkillAccessDenied;
import me.kezhenxu94.springagent.core.skills.SkillFiles;
import me.kezhenxu94.springagent.core.skills.SkillSummary;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@AgentTool
@Component
@RequiredArgsConstructor
public class SkillManagementTools {
  private final UserWorkspaceFactory userWorkspaceFactory;

  /**
   * Where a skill actually lives, and the one guard on reaching it.
   *
   * <p>The browser's skills page calls the same component, which is the point: a path that may not
   * be written has to be refused identically whether a model asked or a person clicked, and two
   * copies of that decision would be one copy fixed.
   */
  private final SkillFiles skills;

  /** What this hands back to the model, in the workspace's language. */
  private final CoreMessages messages;

  /**
   * {@code path}, once {@link SkillFiles} has agreed it is inside the home — or null, with the
   * refusal already turned into the sentence the model reads.
   *
   * <p>The sentence stays here rather than moving with the check. {@code SkillFiles} answers a
   * controller as well, and a controller has no business emitting prose written for a model.
   */
  private Path validated(final String path, final HomeDir home) {
    try {
      return skills.guarded(home, Path.of(path));
    } catch (final SkillAccessDenied | InvalidPathException e) {
      return null;
    }
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
    final List<SkillSummary> found;
    try {
      found = skills.list(userWorkspaceFactory.forRequest(context));
    } catch (final UncheckedIOException e) {
      return messages.get("skill-no-directory", e.getMessage());
    }

    // A name that exists in two of the request's scopes is listed once, because it is one skill to
    // the model: SkillsTool keeps the nearest and drops the rest, so a second line here would name
    // a folder nothing will ever load. SkillFiles resolves that the same way, and once.
    if (found.isEmpty()) return messages.get("skill-none");
    final var result = new StringBuilder();
    for (final var skill : found) {
      result.append(skill.directory().toAbsolutePath()).append("\n");
    }
    return messages.get("skill-found", found.size(), result);
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

    final var file = validated(filePath, home);
    if (file == null) return messages.get("skill-access-denied");

    final boolean existed = Files.isRegularFile(file);
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, content != null ? content : "", StandardCharsets.UTF_8);
    } catch (final IOException e) {
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

    final var dir = validated(skillFolderPath, home);
    if (dir == null) return messages.get("skill-access-denied");

    if (!Files.exists(dir)) return messages.get("skill-folder-missing", skillFolderPath);
    if (!Files.isDirectory(dir)) return messages.get("skill-not-a-directory", skillFolderPath);

    try {
      skills.deleteSkill(home, dir);
    } catch (final SkillAccessDenied e) {
      // The one SkillFiles raises after the path itself was accepted: a folder with no SKILL.md in
      // it. Said as its own sentence, because it is not a refusal about where the folder is.
      return messages.get("skill-not-a-skill", skillFolderPath);
    } catch (final UncheckedIOException e) {
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

    final var file = validated(filePath, home);
    if (file == null) return messages.get("skill-access-denied");

    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      return messages.get("skill-file-missing", filePath);
    }
    if (Files.isDirectory(file)) return messages.get("skill-file-is-directory", filePath);

    try {
      Files.delete(file);
    } catch (final IOException e) {
      return messages.get("skill-file-delete-failed", filePath);
    }
    return messages.get("skill-file-deleted", filePath);
  }
}
