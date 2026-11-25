package me.kezhenxu94.springagent.core.advisors.toolsearch;

import java.util.Map;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * Tells the tool search which group each of this run's tools belongs to — an MCP server, the skills
 * — so that its catalogue lists a group on one line rather than every tool with a description of
 * its own. Only the composition knows where a tool came from: an app-wide MCP server's tools carry
 * no prefix at all.
 *
 * <p>An advisor rather than a field on the composition, because the tool search reads it from the
 * request context and this is what puts things there; ahead of the tool loop, whose first step
 * builds the catalogue.
 *
 * @param groups each grouped tool's group, by tool name; a tool not in it is listed on its own
 */
public record ToolGroupsAdvisor(Map<String, ToolGroup> groups) implements BaseAdvisor {

  /** Where the groups wait for {@link ToolSearchToolCallingAdvisor} in the request context. */
  public static final String CONTEXT_KEY = ToolGroupsAdvisor.class.getName() + ".groups";

  /**
   * One group of tools in the catalogue.
   *
   * @param label what the group is called: an MCP server's name, or "skills"
   * @param prefix what every tool name in it starts with, listed once; empty where they share none
   * @param purpose what the group is for; empty where nothing says
   * @param describeEach whether each tool is listed on a line of its own with the first sentence of
   *     its description, and the purpose in full as how to use them — for skills, which are chosen
   *     by what they say they are for rather than by their names
   */
  public record ToolGroup(String label, String prefix, String purpose, boolean describeEach) {

    /** A group listed by name only, its purpose on one line. */
    public ToolGroup(final String label, final String prefix, final String purpose) {
      this(label, prefix, purpose, false);
    }
  }

  public ToolGroupsAdvisor {
    groups = Map.copyOf(groups);
  }

  @Override
  public ChatClientRequest before(final ChatClientRequest request, final AdvisorChain chain) {
    return request.mutate().context(CONTEXT_KEY, groups).build();
  }

  @Override
  public ChatClientResponse after(final ChatClientResponse response, final AdvisorChain chain) {
    return response;
  }

  @Override
  public int getOrder() {
    return ToolCallingAdvisor.DEFAULT_ORDER - 100;
  }
}
