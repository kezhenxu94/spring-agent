package me.kezhenxu94.springagent.core.tools;

import com.google.common.base.Strings;
import java.util.Locale;
import java.util.Optional;

/**
 * Which of the three identities a request carries something belongs to.
 *
 * <p>Every run has up to three: the person who asked, the group chat they asked in, and the tenant
 * they belong to — the ids {@link ToolContexts#USER_ID}, {@link ToolContexts#GROUP_ID} and {@link
 * ToolContexts#TENANT_ID} carry, and the three homes {@link UserWorkspaceFactory} lays out. Several
 * things a run can store are owned by exactly one of them even though a reader may reach it through
 * any it belongs to: a knowledge document, a memory, a skill, a file.
 *
 * <p>Here rather than inside any one of those, because <b>the words are the shared part</b>. They
 * are what a tool asks the model for and what the model passes back, so {@code own} accepted by one
 * tool and read as something else by the next is a lie told to whoever typed it. Two copies of this
 * list would eventually be two lists; one of them is the point. It sits beside {@code ToolContexts}
 * because that is where the three ids themselves are declared — this names one of them, and the two
 * facts belong together.
 *
 * <p>Read by {@code core.knowledge}, {@code core.memory}, {@code spring-agent-rag-milvus} and the
 * browser's {@code KnowledgeController}.
 */
public enum ScopeTarget {
  OWN,
  GROUP,
  TENANT;

  /** The default for a write that did not say, and for a word that is not one of these. */
  public static ScopeTarget of(final String value) {
    return named(value).orElse(OWN);
  }

  /**
   * The one this word names, or empty if it names none of them.
   *
   * <p>Separate from {@link #of} because falling back to {@code OWN} is right for a write that left
   * the scope out and wrong for one that asked for a scope and misspelt it — moving a company
   * document into a private knowledge base is not a reasonable reading of a typo. A caller that has
   * to tell the two apart asks this one.
   */
  public static Optional<ScopeTarget> named(final String value) {
    if (Strings.isNullOrEmpty(value)) return Optional.empty();
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "own" -> Optional.of(OWN);
      case "group" -> Optional.of(GROUP);
      case "tenant", "company" -> Optional.of(TENANT);
      default -> Optional.empty();
    };
  }

  /**
   * The word a tool call addresses this scope by.
   *
   * <p>Lower case and never localized: it is an argument the model passes back to {@link #named},
   * not prose for a person to read. The sentence around it is what the message bundles translate.
   */
  public String word() {
    return name().toLowerCase(Locale.ROOT);
  }
}
