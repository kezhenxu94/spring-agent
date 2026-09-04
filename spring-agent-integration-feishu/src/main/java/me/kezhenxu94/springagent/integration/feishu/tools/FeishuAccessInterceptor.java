package me.kezhenxu94.springagent.integration.feishu.tools;

import com.google.common.base.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolCallInterceptor;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place a Feishu tool call is stopped before it reaches Feishu.
 *
 * <p>Fifty-odd tools take a document, spreadsheet, base, file, folder or wiki token straight from
 * the model, and every call they make carries the bot's tenant token — so without this the answer
 * to "may I read that document" is whatever the bot can see, for whoever is talking to the bot.
 * Putting the check here rather than at the top of each tool is not tidiness: a check written fifty
 * times is a check the fifty-first tool forgets, and forgetting looks exactly like working. Here,
 * the default for a tool nobody has ruled on is refusal — see {@link FeishuGuardedTools}.
 *
 * <p>A refusal is answered to the model rather than thrown at the run, so the turn carries on and
 * the person is told why. That is what {@link ToolCallInterceptor.CallRefused} is for.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuAccessInterceptor implements ToolCallInterceptor {

  /**
   * Every tool this module publishes is named this way, and nothing else is. Cheap enough to run
   * before parsing anything, which matters because this bean sees every tool call of every run.
   */
  private static final String PREFIX = "Feishu";

  final FeishuDriveAccess driveAccess;
  final JsonMapper objectMapper;
  final FeishuMessages messages;

  @Override
  public String beforeCall(
      final String toolName, final String toolInput, final ToolContext toolContext) {
    if (toolName == null || !toolName.startsWith(PREFIX)) {
      return toolInput;
    }
    final var rules = FeishuGuardedTools.GUARDED.get(toolName);
    if (rules == null) {
      if (FeishuGuardedTools.UNGUARDED.contains(toolName)) {
        return toolInput;
      }
      // Logged at error and pointed at whoever wrote the tool: there is no way for a run to get
      // past this, and no way for the tool to have been used before somebody adds it to the table.
      log.error(
          "Refusing '{}': no access rule for it. Add it to FeishuGuardedTools, to GUARDED with the"
              + " argument that carries its token, or to UNGUARDED if it carries none.",
          toolName);
      throw new CallRefused(messages.get("access-no-rule", toolName));
    }

    final var arguments = arguments(toolName, toolInput);
    for (final var rule : rules) {
      final var raw = arguments == null ? "" : arguments.path(rule.argument()).asString("");
      if (Strings.isNullOrEmpty(raw)) {
        if (rule.required()) {
          throw new CallRefused(messages.get("access-missing-argument", toolName, rule.argument()));
        }
        continue;
      }
      try {
        if (FeishuGuardedTools.WIKI_SPACE.equals(rule.type())) {
          driveAccess.requireWikiSpaceAccess(toolContext, raw);
        } else {
          final var target = target(toolName, rule, arguments, raw);
          driveAccess.requireAccess(toolContext, target.token(), target.type());
        }
      } catch (FeishuDriveAccess.DriveAccessDeniedException e) {
        throw new CallRefused(e.getMessage());
      }
    }
    return toolInput;
  }

  /**
   * What this argument names, and what kind of thing it is.
   *
   * <p>Resolved through {@link FeishuGuardedTools#resolve}, the same code the tools that take a
   * link use, so that the thing checked here and the thing opened there cannot come out as two
   * different documents.
   *
   * <p>Most rules write the type down. The two that cannot carry {@code $argument} instead, because
   * the tool is told what it is being given — an export names the kind of document it is exporting
   * — and {@code $argument|fallback} where that argument is optional and a link may say it instead.
   */
  private FeishuGuardedTools.Resolved target(
      final String toolName,
      final FeishuGuardedTools.Guarded rule,
      final JsonNode arguments,
      final String raw) {

    if (!rule.type().startsWith("$")) {
      return FeishuGuardedTools.resolve(raw, rule.type());
    }
    final var spec = rule.type().substring(1);
    final var separator = spec.indexOf('|');
    final var typeArgument = separator < 0 ? spec : spec.substring(0, separator);
    final var fallback = separator < 0 ? null : spec.substring(separator + 1);

    final var declared = arguments == null ? "" : arguments.path(typeArgument).asString("");
    final var resolved =
        FeishuGuardedTools.resolve(raw, Strings.isNullOrEmpty(declared) ? null : declared);
    if (!Strings.isNullOrEmpty(resolved.type())) {
      return resolved;
    }
    if (fallback != null) {
      return new FeishuGuardedTools.Resolved(resolved.token(), fallback);
    }
    throw new CallRefused(
        messages.get("access-missing-type", toolName, typeArgument, rule.argument()));
  }

  /**
   * The call's arguments, or null where they could not be read.
   *
   * <p>Null rather than a refusal of its own, because the rules then see every argument as absent:
   * a required one refuses and an optional one is skipped, which is the same fail-closed answer
   * arrived at without this having to guess what a malformed call meant.
   */
  private JsonNode arguments(final String toolName, final String toolInput) {
    if (Strings.isNullOrEmpty(toolInput)) {
      return null;
    }
    try {
      return objectMapper.readTree(toolInput);
    } catch (Exception e) {
      log.warn("Could not read the arguments of '{}': {}", toolName, e.getMessage());
      return null;
    }
  }
}
