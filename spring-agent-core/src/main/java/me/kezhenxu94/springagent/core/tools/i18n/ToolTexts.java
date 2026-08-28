package me.kezhenxu94.springagent.core.tools.i18n;

/**
 * The model-facing text of one module's tools, in the workspace's language.
 *
 * <p>What a tool tells the model it is for comes from {@code @Tool} and {@code @ToolParam}, whose
 * values are compile-time constants and so cannot be read from a bundle where they are written.
 * What this serves is therefore an <em>override</em>, applied to the definition on its way to the
 * model by {@link LocalizingToolCallingManager}. English stays in the annotation and is what an
 * untranslated tool falls back to, so nothing here is ever required: a language can be translated
 * one tool at a time, and a tool nobody names — every tool of every MCP server a user registered,
 * whose names no module can know in advance — keeps exactly what it declares.
 *
 * <p>One implementation per module rather than one for the whole application, because the tools are
 * per module and so are the translations: two thirds of them belong to {@code
 * spring-agent-integration-feishu}, whose resources core cannot see and whose classes a test in
 * core cannot enumerate. The same reasoning that gave {@code FeishuMessages} a message source of
 * its own — "two modules claiming one basename would be a fight over which one wins". A module
 * contributes one of these as a bean; a module with nothing to translate contributes none.
 */
public interface ToolTexts {

  /** The translated description of the named tool, or null to keep what it declares. */
  String description(String toolName);

  /** The translated description of one parameter, or null to keep what it declares. */
  String parameter(String toolName, String parameterName);

  /**
   * Whether this module translates anything at all about the tool — which is what decides whether
   * its input schema is worth parsing. Asked about every tool of every request, so it has to be
   * cheap and has to answer the same way every time; see {@link ModuleToolTexts} for why that
   * matters more than it looks.
   */
  boolean covers(String toolName);
}
