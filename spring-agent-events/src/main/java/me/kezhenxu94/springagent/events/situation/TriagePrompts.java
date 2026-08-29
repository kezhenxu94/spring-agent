package me.kezhenxu94.springagent.events.situation;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.kezhenxu94.springagent.core.config.LocalizedPrompt;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.springframework.stereotype.Component;

/**
 * What a triage run is told it is doing, per source, in the workspace's language.
 *
 * <p>Files rather than constants, for the reason {@code PromptDefaults} gives about core's own
 * prompts: these are the largest model-facing text in this module by a wide margin, and a paragraph
 * of instructions folded into a Java string or a properties value is neither writable nor
 * reviewable — nor, until this existed, translatable at all.
 *
 * <p>Resolved per source rather than contributed to the environment as {@code PromptDefaults} does,
 * and that difference is the whole reason this class exists. A property has one value, so supplying
 * one would mean every source shares a prompt; but the sources are asking genuinely different
 * questions. An alert wants "does this deserve anybody's attention", and a group chat wants "is
 * there an unanswered question here that you can answer well" — the same words would be wrong for
 * one of them however they were phrased.
 *
 * <p>So a source may ship {@code events/prompts/<source>-triage-prompt.md}, and anything without
 * one gets {@code triage-prompt.md}. Adding a prompt for a new source is adding a file, and
 * translating one is adding a file beside it; neither needs a line of code. A deployment that wants
 * to override either still can, through {@code app.events.triage-prompt} or the per-source setting,
 * which is why the sweeper asks this only once configuration has said nothing.
 */
@Component
public class TriagePrompts {

  /** Where this module's prompts live, as a classpath location. */
  public static final String LOCATION = "events/prompts/";

  /** What a source that ships no prompt of its own is given. */
  static final String DEFAULT_PROMPT = "triage-prompt";

  private final Locale locale;

  /**
   * Read once per source. These are files on the classpath and the answer cannot change while the
   * process runs, whereas a triage happens on a timer and would otherwise re-read them for ever.
   */
  private final Map<String, String> resolved = new ConcurrentHashMap<>();

  public TriagePrompts(final SpringAgentProperties properties) {
    this.locale = properties.locale();
  }

  public String forSource(final String source) {
    return resolved.computeIfAbsent(
        source,
        name ->
            LocalizedPrompt.findText(LOCATION, name + "-" + DEFAULT_PROMPT, locale)
                // text, not findText: the base file ships with this module, so nothing to fall back
                // to means the module was built wrong and failing loudly is the only honest answer.
                .orElseGet(() -> LocalizedPrompt.text(LOCATION, DEFAULT_PROMPT, locale)));
  }
}
