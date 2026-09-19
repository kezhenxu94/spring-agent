package me.kezhenxu94.springagent.core.config;

import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Who may change what the whole company shares, from {@code app.ai.non-admin-tenant-writes}.
 *
 * <p>The tenant scope is the one every colleague reads without asking for it: a memory there is
 * recalled as fact, a skill there is loaded as instructions the agent acts on, and a knowledge
 * document there is retrieved into answers. Nothing reviews any of it on the way in, and the person
 * it misleads is never the person who wrote it. So by default only {@link Admins} may write it —
 * the set this deployment already trusts with everybody else's work — and a deployment that would
 * rather have every member able to write it says so once, here.
 *
 * <p>A class of its own for the reason {@link Admins} is one: the question is asked from three
 * places with nothing else in common — the memory tools, the skills (both the model's and the
 * browser's) and the knowledge base — and three copies of "admin, or the property" would be two
 * copies left behind the next time it changes.
 *
 * <p>This governs <em>writes</em> only. Reading the company scope is what it exists for, and a
 * member who cannot write it still reads every word of it.
 */
@Component
public class TenantWrites {

  private final SpringAgentProperties properties;
  private final Admins admins;

  public TenantWrites(final SpringAgentProperties properties, final Admins admins) {
    this.properties = properties;
    this.admins = admins;
  }

  /** Whether a non-admin may write the company scope at all — the toggle itself. */
  public boolean openToEveryone() {
    return Boolean.TRUE.equals(properties.ai().nonAdminTenantWrites());
  }

  /** Whether {@code userId} may write the company scope. */
  public boolean allowed(final String userId) {
    return openToEveryone() || admins.isAdmin(userId);
  }

  /**
   * The same for the person a tool call belongs to.
   *
   * <p>Reads the id rather than requiring it, exactly as {@link Admins#isAdmin(ToolContext)} does:
   * a run with nobody behind it is not an admin, and answering from the toggle alone is the only
   * answer that does not throw out of a tool with something better to say.
   */
  public boolean allowed(final ToolContext context) {
    return allowed(ToolContexts.get(context, ToolContexts.USER_ID));
  }
}
