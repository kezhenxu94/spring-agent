package me.kezhenxu94.springagent.core.tools.interceptors;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lets a tool argument be given as a reference to a file a previous tool call was spilled to,
 * instead of the content itself.
 *
 * <p>The waste this removes is a tool whose output has no purpose but to become another tool's
 * input: the model emits every byte of it and reads every byte back, paying for the same payload
 * twice in the turn and again in every later turn that replays the conversation. A reference costs
 * one line. {@link LargeResponseInterceptor} writes the file and tells the model the path.
 *
 * <p><b>Which parameters accept one is an allow-list, and that is the security boundary.</b>
 * Expanding any string argument of any tool would make every tool a file-reading primitive, and a
 * run does not always act on behalf of somebody trustworthy — an observation carries evidence
 * written by whoever caused the event, so a triage run reads attacker-authored text. Confining
 * reads to the workspace is no defence there, because the workspace is the interesting part: it
 * would take one injected sentence to have a message-sending tool address a file of memories. So a
 * parameter takes a reference only where a module said it does, and where its own description says
 * so to the model.
 *
 * <p>A value that looks like a reference on a parameter that does not take one is an error rather
 * than text, because the alternative is writing {@code @file:/...} into somebody document as
 * content and finding out much later. {@code @@file:} escapes, for the rare value that really is
 * that text. Only top-level arguments are looked at.
 */
@Slf4j
@Component
public class ToolInputFileRefs {

  /** What marks an argument as a reference rather than content. */
  static final String PREFIX = "@file:";

  /** What a value starts with to mean the literal {@link #PREFIX} and no expansion. */
  static final String ESCAPED_PREFIX = "@" + PREFIX;

  /** Separates the path from the JSON Pointer selecting the part of it that is wanted. */
  static final char POINTER_SEPARATOR = '#';

  /** The one directory a reference may name, under each scope the request can reach. */
  static final String TOOL_RESULTS_DIR = "tool-results";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final SpringAgentProperties appConfiguration;
  private final UserWorkspaceFactory userWorkspaceFactory;
  private final CoreMessages messages;

  /** Parameter names that take a reference, by tool name, merged from every contributing module. */
  private final Map<String, Set<String>> allowed;

  public ToolInputFileRefs(
      final SpringAgentProperties appConfiguration,
      final UserWorkspaceFactory userWorkspaceFactory,
      final CoreMessages messages,
      final List<Params> params) {
    this.appConfiguration = appConfiguration;
    this.userWorkspaceFactory = userWorkspaceFactory;
    this.messages = messages;
    this.allowed = new HashMap<>();
    for (final var contribution : params) {
      contribution
          .byTool()
          .forEach(
              (tool, names) -> allowed.computeIfAbsent(tool, t -> new TreeSet<>()).addAll(names));
    }
    log.debug("Tool parameters that accept a file reference: {}", allowed);
  }

  /**
   * The parameters one module's tools accept a reference on, as a bean.
   *
   * <p>A module declares its own rather than core keeping a list of every module's tools, which
   * would put the names of tools core cannot see into core.
   */
  @FunctionalInterface
  public interface Params {
    /** Parameter names that take a file reference, by the tool name they belong to. */
    Map<String, Set<String>> byTool();
  }

  /** A reference that cannot be honoured; the message is written for the model to act on. */
  public static class UnresolvableReference extends RuntimeException {
    public UnresolvableReference(final String message) {
      super(message);
    }
  }

  /**
   * The arguments with every reference replaced by what it points at, or the same string when there
   * is nothing to replace.
   *
   * @throws UnresolvableReference when a reference cannot be honoured, carrying the reason to hand
   *     back to the model as the call's result
   */
  public String expand(
      final String toolName, final String toolInput, final ToolContext toolContext) {
    if (toolInput == null || !toolInput.contains(PREFIX)) {
      return toolInput;
    }

    final JsonNode parsed;
    try {
      parsed = MAPPER.readTree(toolInput);
    } catch (Exception e) {
      // Not JSON, so it has no arguments to substitute into. Whatever this callback is, it is not
      // one whose input this can meaningfully rewrite.
      return toolInput;
    }
    if (parsed == null || !parsed.isObject()) {
      return toolInput;
    }

    final var arguments = (ObjectNode) parsed;
    final var takesReference = allowed.getOrDefault(toolName, Set.of());
    var changed = false;

    for (final var property : List.copyOf(arguments.properties())) {
      final var value = property.getValue();
      if (!value.isString()) {
        continue;
      }
      final var text = value.stringValue();
      if (text.startsWith(ESCAPED_PREFIX)) {
        arguments.put(property.getKey(), text.substring(1));
        changed = true;
      } else if (text.startsWith(PREFIX)) {
        if (!takesReference.contains(property.getKey())) {
          throw new UnresolvableReference(
              messages.get(
                  "tool-input-ref-not-accepted",
                  property.getKey(),
                  toolName,
                  takesReference.isEmpty() ? "-" : String.join(", ", takesReference),
                  ESCAPED_PREFIX));
        }
        arguments.put(property.getKey(), resolve(text.substring(PREFIX.length()), toolContext));
        changed = true;
      }
    }

    return changed ? MAPPER.writeValueAsString(arguments) : toolInput;
  }

  /** What {@code <path>} or {@code <path>#<pointer>} stands for, as the string to substitute in. */
  private String resolve(final String reference, final ToolContext toolContext) {
    final var separator = reference.indexOf(POINTER_SEPARATOR);
    final var rawPath = separator < 0 ? reference : reference.substring(0, separator);
    final var pointer = separator < 0 ? null : reference.substring(separator + 1);

    final var file = verified(rawPath, toolContext);
    final var maxChars = appConfiguration.ai().tools().maxInlinedInputChars();
    try {
      // Checked before reading rather than after: the point of the cap is not to hold a file this
      // big in memory in the first place. UTF-8 is never fewer bytes than characters, so a file
      // under the cap in bytes is under it in characters too.
      if (Files.size(file) > maxChars) {
        throw new UnresolvableReference(
            messages.get("tool-input-ref-too-large", file, Files.size(file), maxChars));
      }
      final var content = Files.readString(file);
      final var resolved = pointer == null ? content : select(content, pointer, file);
      if (resolved.length() > maxChars) {
        throw new UnresolvableReference(
            messages.get("tool-input-ref-too-large", file, resolved.length(), maxChars));
      }
      return resolved;
    } catch (MalformedInputException e) {
      throw new UnresolvableReference(messages.get("tool-input-ref-not-text", file));
    } catch (IOException e) {
      throw new UnresolvableReference(
          messages.get("tool-input-ref-unreadable", file, e.getMessage()));
    }
  }

  /** The part of the file the pointer selects, as text a tool parameter can be given. */
  private String select(final String content, final String pointer, final Path file) {
    final JsonNode root;
    try {
      root = MAPPER.readTree(content);
    } catch (Exception e) {
      throw new UnresolvableReference(messages.get("tool-input-ref-not-json", file, pointer));
    }
    final JsonNode selected;
    try {
      selected = root.at(JsonPointer.compile(pointer));
    } catch (IllegalArgumentException e) {
      throw new UnresolvableReference(messages.get("tool-input-ref-bad-pointer", pointer));
    }
    if (selected.isMissingNode()) {
      throw new UnresolvableReference(
          messages.get("tool-input-ref-pointer-missing", pointer, file, keysOf(root)));
    }
    // A selected string is handed over as itself: a parameter asking for text wants the text, not
    // the same text wrapped in quotes.
    return selected.isString() ? selected.stringValue() : MAPPER.writeValueAsString(selected);
  }

  /**
   * The file the reference names, once it is certain to be one this request may read.
   *
   * <p>The rule is deliberately narrower than "inside the workspace": a reference may only name a
   * file that really lies directly inside a {@code tool-results} directory of one of the scopes
   * this request reaches. Both sides of that comparison are resolved to their real paths, which
   * settles traversal, a symlinked directory and — the one that is easy to miss — an innocent name
   * in the right directory that is itself a link to a file somewhere else entirely.
   */
  private Path verified(final String rawPath, final ToolContext toolContext) {
    if (toolContext == null) {
      // No context is no identity, and no identity is no workspace to resolve against. Guessing one
      // would be guessing whose files these are.
      throw new UnresolvableReference(messages.get("tool-input-ref-no-context"));
    }
    final Path candidate;
    try {
      candidate = Path.of(rawPath);
    } catch (InvalidPathException e) {
      throw new UnresolvableReference(messages.get("tool-input-ref-not-found", rawPath));
    }
    if (candidate.getFileName() == null || candidate.getParent() == null) {
      throw new UnresolvableReference(messages.get("tool-input-ref-not-found", rawPath));
    }
    if (!Files.isRegularFile(candidate)) {
      throw new UnresolvableReference(messages.get("tool-input-ref-not-found", rawPath));
    }

    final var home = userWorkspaceFactory.forRequest(toolContext);
    try {
      // The file itself, not the path that was written: a link in the right directory pointing
      // anywhere at all would otherwise pass this.
      final var parent = candidate.toRealPath().getParent();
      // Every scope the request reaches, exactly as reading a file does: a group chat's tool
      // results belong to the run as much as the personal ones do.
      for (final var artifacts : home.dirs(HomeDir.Folder.ARTIFACTS)) {
        final var toolResults = artifacts.resolve(TOOL_RESULTS_DIR);
        if (Files.isDirectory(toolResults) && parent.equals(toolResults.toRealPath())) {
          return candidate;
        }
      }
    } catch (IOException e) {
      throw new UnresolvableReference(messages.get("tool-input-ref-not-found", rawPath));
    }
    throw new UnresolvableReference(messages.get("tool-input-ref-outside", rawPath));
  }

  private static String keysOf(final JsonNode root) {
    if (!root.isObject()) {
      return root.isArray() ? "an array of " + root.size() + " items" : "not an object";
    }
    final var names = new TreeSet<String>();
    root.propertyNames().forEach(names::add);
    return String.join(", ", names);
  }
}
