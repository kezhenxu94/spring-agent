package me.kezhenxu94.springagent.events.aot;

import me.kezhenxu94.springagent.events.config.EventsMessages;
import me.kezhenxu94.springagent.events.situation.TriagePrompts;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * The resources this module reads at runtime, so that a native image still has them.
 *
 * <p>All of it is text, and none of it is optional. A prompt that fails to load fails the run; a
 * message that fails to load leaves the agent reading a brief made of message keys. Both are the
 * kind of failure a native image introduces on its own, since the JVM build finds these on the
 * classpath and says nothing.
 *
 * <p>No reflection hints. The tools are found through {@code @AgentTool} and get theirs from
 * Spring's own AOT processing, as core's {@code AgentToolsRuntimeHints} explains. The readers for
 * each system live in their own modules and take their payloads as trees rather than binding them
 * to types, so they need none either.
 */
public class EventsRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    // What a triage run is told it is doing, per source and per language.
    //
    // One pattern for every module's, not just this one's. A source ships its prompt at this
    // location from its own jar — spring-agent-integration-github does, and so does the Feishu
    // integration for the chat it watches — and a resource pattern is matched against the whole
    // classpath rather than against the module that registered it. So this covers them, and a new
    // integration adds a prompt without adding a hint.
    hints.resources().registerPattern(TriagePrompts.LOCATION + "*.md");

    // Each tool's description in the workspace's language, one file per tool. A pattern of its own
    // because the one above does not cross a directory separator.
    hints.resources().registerPattern(TriagePrompts.LOCATION + "tools/*.md");

    // And each parameter's. A plain resource pattern, not registerResourceBundle: ModuleToolTexts
    // reads these as resources precisely so as not to go through a ResourceBundle, which would
    // consult the host's locale before the base file.
    hints.resources().registerPattern("events/tools.properties");
    hints.resources().registerPattern("events/tools_*.properties");

    // The brief's own words, which do go through a ResourceBundle.
    hints.resources().registerResourceBundle(EventsMessages.BASENAME);
  }
}
