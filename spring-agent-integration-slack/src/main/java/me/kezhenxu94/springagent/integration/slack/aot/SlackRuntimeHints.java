package me.kezhenxu94.springagent.integration.slack.aot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Resource hints for everything this module reads off the classpath at runtime, none of which the
 * closed-world analysis can reach from the bytecode.
 *
 * <p>The failure a missing hint here produces is the worst shape a bug can have: the JVM build
 * passes and the binary breaks, or worse, quietly finds nothing — a deployment whose update notes
 * cannot be read tells every user they are up to date.
 */
public class SlackRuntimeHints implements RuntimeHintsRegistrar {

  /** The messages' own words, whose locale is only known when the binary runs. */
  private static final String MESSAGES_BUNDLE = "slack.messages";

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    hints.resources().registerResourceBundle(MESSAGES_BUNDLE);

    // Each tool's own description, in whichever language the deployment configured. Read while a
    // run is being answered, so a missing one fails the call rather than losing a paragraph.
    hints.resources().registerPattern("slack/prompts/*.md");
    hints.resources().registerPattern("slack/prompts/tools/*.md");

    // And each tool parameter's description. A plain resource pattern rather than a resource
    // bundle: ModuleToolTexts reads these as resources precisely so as not to go through a
    // ResourceBundle, which would consult the host's locale ahead of the base file.
    hints.resources().registerPattern("slack/tools.properties");
    hints.resources().registerPattern("slack/tools_*.properties");

    // The greeting and the numbered update notes, in every language they ship in. They are read by
    // the name each must have rather than by listing the directory — a native image has no
    // classpath directory listing, so a scan would find nothing while the JVM build worked.
    hints.resources().registerPattern("slack/welcome*.md");
    hints.resources().registerPattern("slack/updates/*.md");

    // No Block Kit templates: unlike the Feishu card, every block this module writes is built in
    // Java from the run's state, because chat.update replaces a message wholesale and there is no
    // template to fill in. What a deployment rewrites is the prose above, not a layout.
  }
}
