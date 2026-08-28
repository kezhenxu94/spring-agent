package me.kezhenxu94.springagent.integration.feishu.config;

import java.util.Locale;
import lombok.Getter;
import me.kezhenxu94.springagent.core.config.LocalizedPrompt;

/**
 * The reference pages three of the Feishu tools hand back as their whole result — a field-type
 * table, a filter grammar, a document-block grammar, a table of cell formats — in the workspace's
 * language.
 *
 * <p>Files rather than string constants, for the reason core's prompts are files: these run to
 * sixty lines apiece and four hundred together, and a page of grammar folded into a Java literal is
 * neither writable nor reviewable. It also made them untranslatable, since the tool returned the
 * constant.
 *
 * <p>Read when the context starts rather than per call. They are the same every time, and a
 * translation left out of the jar then fails the deployment that is missing it instead of one tool
 * call, weeks later, in the one language nobody runs.
 *
 * <p>Note what these are <em>not</em>: they are handed to the model as a tool's output and are
 * never rendered as a template. So the braces all over them — {@code {"type": "formula"}} and the
 * like — are literal, and must stay that way. That is the opposite of {@code
 * core/prompts/auto-memory.md}, whose every brace has to be doubled because the advisor pushes it
 * through a {@code PromptTemplate}, and which has a test saying so; do not extend that test to
 * these.
 */
@Getter
public class FeishuGuides {

  /** Where the guides live, as a classpath location. */
  static final String LOCATION = "feishu/prompts/";

  /** Where this module's per-tool description translations live. */
  public static final String TOOLS_LOCATION = LOCATION + "tools/";

  private final String bitableFieldReference;
  private final String bitableFilterGuide;
  private final String docBlockGuide;
  private final String docBlockContentReference;
  private final String sheetDataFormats;

  /**
   * @param locale which language to read, the host's when {@code null}
   */
  public FeishuGuides(final Locale locale) {
    this.bitableFieldReference = LocalizedPrompt.text(LOCATION, "bitable-field-reference", locale);
    this.bitableFilterGuide = LocalizedPrompt.text(LOCATION, "bitable-filter-guide", locale);
    this.docBlockGuide = LocalizedPrompt.text(LOCATION, "doc-block-guide", locale);
    this.docBlockContentReference =
        LocalizedPrompt.text(LOCATION, "doc-block-content-reference", locale);
    this.sheetDataFormats = LocalizedPrompt.text(LOCATION, "sheet-data-formats", locale);
  }
}
