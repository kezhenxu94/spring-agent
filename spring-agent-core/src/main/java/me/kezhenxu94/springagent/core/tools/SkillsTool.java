package me.kezhenxu94.springagent.core.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.SkillsTool.Skill;
import org.springaicommunity.agent.utils.Skills;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

/**
 * Every installed skill as a tool of its own, named after the skill and taking no arguments.
 *
 * <p>A skill called {@code fill-a-pdf} is the tool {@code skill_fill-a-pdf}; calling it returns
 * that skill's instructions and the directory they live in.
 *
 * <p>Forked from {@code org.springaicommunity.agent.tools.SkillsTool}, which builds one {@code
 * Skill} tool whose description embeds a catalog of every skill and which is invoked as {@code
 * Skill(command="pdf")}. That shape does not survive a tool search: the catalog lives inside one
 * tool description, so the index holds one document carrying every skill's front matter, which
 * cannot be selected between — a search for "fill in a PDF" either loads all of them or none. Past
 * roughly a hundred skills it stops working altogether, since that one description outgrows the
 * vector store's own limit on a field (Milvus refuses a varchar over 65535 bytes) and the run fails
 * while the index is being built.
 *
 * <p>One tool per skill makes choosing a skill ordinary tool selection: each skill is its own
 * document in the index, is retrieved on its own merits, and a run is handed the two or three it
 * actually needs rather than a list of a hundred. The cost is paid where a deployment has the tool
 * search off — the model then sees one tool per skill, where before it saw one — which is the
 * trade-off {@code spring.ai.chat.client.tool-search-advisor.enabled} exists to make.
 *
 * <p>This is <a
 * href="https://github.com/spring-ai-community/spring-ai-agent-utils/pull/73">upstream PR #73</a>,
 * applied here while it waits. When it is released this class is deleted and the import points back
 * at the library — {@link Skill} is deliberately still the library's record rather than a copy, so
 * nothing else here has to change with it, and {@code Skills} keeps loading skills.
 *
 * <p>Two things deviate from that PR, and both have to. The tool name carries a {@code skill_}
 * prefix — see {@link #TOOL_NAME_PREFIX} — because here a skill name is written by the user rather
 * than shipped with an application, so without one a skill could be named over a tool the run
 * already carries.
 *
 * <p>And the second: where the PR fails {@code build()} on a skill it cannot register — a duplicate
 * name, a name too long to be a tool name, no name at all — this skips it and logs. A throw here
 * fails the whole composition, which means every run of whoever owns that skill fails, including
 * the run that would have been told to rename it: the tools that rewrite a skill are composed by
 * the same call that just threw, so the user is left with no way to reach them from the chat.
 * Skipping costs one skill; throwing costs the agent.
 *
 * <p>Skipping a duplicate keeps the <em>first</em> one, which is what makes {@code
 * HomeDir.dirs(SKILLS)} order meaningful: it answers with the nearest scope first, so a user's own
 * skill shadows the group's and the group's shadows the tenant's, rather than a shared skill
 * quietly replacing a private one of the same name.
 *
 * @author Christian Tzolov
 * @author kezhenxu94
 */
public class SkillsTool {

  private static final Logger log = LoggerFactory.getLogger(SkillsTool.class);

  /**
   * What the model providers do not accept in a tool name — they take {@code
   * ^[a-zA-Z0-9_-]{1,64}$}, and a scoped skill name like {@code project-a:pdf} is a legitimate
   * skill name carrying a character none of them allow. The skill keeps its name; only the derived
   * tool name is rewritten.
   */
  private static final Pattern ILLEGAL_TOOL_NAME_CHARS = Pattern.compile("[^a-zA-Z0-9_-]");

  /** The longest tool name the model providers accept. */
  private static final int MAX_TOOL_NAME_LENGTH = 64;

  /**
   * What every skill's tool name begins with, so that a skill cannot be named over a tool the run
   * already carries.
   *
   * <p>Upstream's PR names the tool exactly after the skill; the prefix is here because of who
   * writes that name. A skill is a file — written by the user, or by the model on their behalf with
   * {@code WriteSkillFile} — and a skill name is now a tool name, so without a prefix a skill
   * called {@code Read} arrives in the same namespace as the file tool. Two tools of one name is a
   * request every provider rejects, and the run that would have renamed the skill is composed the
   * same way, so it fails too: the user is left with no way to reach the tools that would fix it.
   * Six characters buys that whole class of failure away, and the model reads {@code
   * skill_fill-a-pdf} as what it is.
   *
   * <p>It also leaves the name a skill's <em>own</em> words are searched by intact, which is what
   * makes the tool search find it — the prefix is a namespace, not a rewrite.
   */
  private static final String TOOL_NAME_PREFIX = "skill_";

  /**
   * What surrounds a skill's own front matter in the tool's description, and the seam a deployment
   * translates: the {@code %s} is one skill's front matter, never the catalog it was before the
   * fork.
   *
   * <p>It says only what the schema cannot. The name is the tool's name, the description is the
   * front matter, and calling it takes no arguments — so all that is left to explain is that the
   * answer opens with the skill's directory, which is what a skill's own scripts and sibling files
   * are reached through.
   */
  private static final String TOOL_DESCRIPTION_TEMPLATE =
      """
      %s

      Returns the instructions to follow for this skill, preceded by a "Base directory for this skill: <path>" line - use that path to read the skill's other files or run its scripts.
      """;

  /** One skill's instructions. Takes no input: the tool called <em>is</em> the choice of skill. */
  public static class SkillFunction implements Supplier<String> {

    private final Skill skill;

    public SkillFunction(final Skill skill) {
      this.skill = skill;
    }

    @Override
    public String get() {
      return "Base directory for this skill: %s\n\n%s"
          .formatted(this.skill.basePath(), this.skill.content());
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private final List<Skill> skills = new ArrayList<>();

    private String toolDescriptionTemplate = TOOL_DESCRIPTION_TEMPLATE;

    protected Builder() {}

    /**
     * @param template the description every skill tool gets, over a single {@code %s} filled with
     *     that skill's own front matter.
     */
    public Builder toolDescriptionTemplate(final String template) {
      this.toolDescriptionTemplate = template;
      return this;
    }

    public Builder addSkillsResources(final List<Resource> skillsResources) {
      this.skills.addAll(Skills.loadResources(skillsResources));
      return this;
    }

    public Builder addSkillsResource(final Resource skillsResource) {
      this.skills.addAll(Skills.loadResource(skillsResource));
      return this;
    }

    public Builder addSkillsDirectory(final String skillsRootDirectory) {
      this.addSkillsDirectories(List.of(skillsRootDirectory));
      return this;
    }

    public Builder addSkillsDirectories(final List<String> skillsRootDirectories) {
      for (final var skillsRootDirectory : skillsRootDirectories) {
        this.skills.addAll(Skills.loadDirectory(skillsRootDirectory));
      }
      return this;
    }

    /**
     * One callback per skill that can be registered as a tool, in the order the skills were added.
     *
     * <p>Throws only where nothing was added at all, which is a caller that has not looked at
     * whether the user has any skills. A skill that cannot be a tool is skipped, for the reason the
     * class comment gives.
     */
    public ToolCallbackProvider build() {
      Assert.notEmpty(this.skills, "At least one skill must be configured");

      final var byToolName = new LinkedHashMap<String, Skill>();
      for (final var skill : this.skills) {
        final var toolName = toolName(skill);
        if (toolName == null) {
          continue;
        }
        final var previous = byToolName.putIfAbsent(toolName, skill);
        if (previous != null) {
          log.warn(
              "Skipping skill '{}' in '{}': its tool name '{}' is already taken by the skill in"
                  + " '{}'. Rename one of the two, or the nearer one is the only one offered.",
              name(skill),
              skill.basePath(),
              toolName,
              previous.basePath());
        }
      }

      return ToolCallbackProvider.from(
          byToolName.entrySet().stream()
              .map(entry -> this.toToolCallback(entry.getKey(), entry.getValue()))
              .toList());
    }

    private ToolCallback toToolCallback(final String toolName, final Skill skill) {
      return FunctionToolCallback.builder(toolName, new SkillFunction(skill))
          .description(this.toolDescriptionTemplate.formatted(toDescription(skill)))
          .build();
    }
  }

  /**
   * The tool name for {@code skill}, or null where the skill cannot be offered as one at all.
   *
   * <p>{@link #TOOL_NAME_PREFIX} then whatever of the skill's own name a provider accepts. A skill
   * with no {@code name} in its front matter is one nothing can call, and a name too long for a
   * tool name is one every provider would reject the whole request over — so both are left out
   * rather than allowed to take the run down with them.
   */
  private static String toolName(final Skill skill) {
    final var name = name(skill);
    if (name == null || name.isBlank()) {
      log.warn(
          "Skipping the skill in '{}': its SKILL.md has no 'name' in the front matter, so there is"
              + " nothing to name a tool after.",
          skill.basePath());
      return null;
    }

    final var toolName = TOOL_NAME_PREFIX + ILLEGAL_TOOL_NAME_CHARS.matcher(name).replaceAll("_");
    if (toolName.length() > MAX_TOOL_NAME_LENGTH) {
      log.warn(
          "Skipping skill '{}' in '{}': a skill name becomes the tool name '{}' and must be at most"
              + " {} characters, {} of them spent on the prefix.",
          name,
          skill.basePath(),
          toolName,
          MAX_TOOL_NAME_LENGTH,
          TOOL_NAME_PREFIX.length());
      return null;
    }

    return toolName;
  }

  /**
   * What the front matter calls the skill, without {@link Skill#name()}'s assumption that it says
   * so at all — that reads the map and dereferences whatever it finds.
   */
  private static String name(final Skill skill) {
    final var name = skill.frontMatter().get("name");
    return name == null ? null : name.toString();
  }

  /**
   * A skill's front matter as the plain text of its tool's description: what it says it does,
   * followed by whatever else it declares ({@code allowed-tools}, {@code model}) as {@code key:
   * value} lines.
   *
   * <p>Plain text rather than the pseudo-XML {@link Skill#toXml()} produces, which is what a single
   * catalog needed to keep one skill's fields apart from the next one's. A description of its own
   * has no neighbours, and a {@code <} or a stray {@code </skill>} in a description would have
   * corrupted the structure of the prompt around it.
   */
  private static String toDescription(final Skill skill) {
    final var description = String.valueOf(skill.frontMatter().getOrDefault("description", ""));
    final var remaining =
        skill.frontMatter().entrySet().stream()
            .filter(
                entry -> !"name".equals(entry.getKey()) && !"description".equals(entry.getKey()))
            .map(entry -> "%s: %s".formatted(entry.getKey(), entry.getValue()))
            .collect(Collectors.joining("\n"));

    return remaining.isEmpty() ? description : description + "\n\n" + remaining;
  }
}
