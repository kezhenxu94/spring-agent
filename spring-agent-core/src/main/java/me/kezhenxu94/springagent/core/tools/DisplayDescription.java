package me.kezhenxu94.springagent.core.tools;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one parameter every tool is offered whether it declares it or not: a sentence saying what
 * this particular call does, written for the person watching the agent work and never passed to the
 * tool.
 *
 * <p>A surface names a call by its tool and, where there is one, by what the model said the call
 * was for — {@code Bash} twenty times over is a trail that says nothing, and the sentence is the
 * one thing that tells one of those calls from the next without opening it. A few tools ask for one
 * in their own schema; every MCP server's tool asks for nothing of the sort, and there is no asking
 * their authors to. So the runtime asks instead, on the way out to the model, and takes the answer
 * back off the arguments on the way in to the tool.
 *
 * <p><b>The field is named for the runtime rather than for the tool</b>, and that is what makes
 * both halves of this safe to do unconditionally. Reusing {@code description} would mean the strip
 * having to guess from the schema whether the runtime put it there, and guessing wrong on a tool
 * that takes arbitrary JSON — {@code additionalProperties: true} with no {@code description}
 * declared — would eat an argument the model meant for the tool. A reserved name is never anybody's
 * argument, so it is always ours to remove.
 *
 * <p><b>Required, so that every call has a title.</b> Left optional it is skipped on most calls by
 * a model answering a non-strict schema, which buys a trail that is readable for some of a turn and
 * bare for the rest — the calls a reader is trying to tell apart being exactly the ones a model in
 * a hurry describes least. It costs a sentence per call, which is the price of the feature.
 *
 * <p>Required is safe here because the argument never reaches the tool: nothing behind it can
 * refuse a call for carrying a field its own schema does not declare, since by then the field is
 * gone. What the schema demands and what the tool receives are two different things on purpose.
 */
@Slf4j
public final class DisplayDescription {

  /**
   * JSON in a schema and in arguments, not in a document: no application configuration reaches it.
   */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** The parameter's name, in the schema the model reads and in the arguments it writes back. */
  public static final String FIELD = "_display_description";

  /** What a tool's own schema calls the same thing, where it has one; that one is left alone. */
  public static final String NATIVE_FIELD = "description";

  private static final String PROPERTIES = "properties";
  private static final String TYPE = "type";
  private static final String STRING = "string";
  private static final String ADDITIONAL_PROPERTIES = "additionalProperties";
  private static final String REQUIRED = "required";

  private DisplayDescription() {}

  /**
   * The schema with the parameter added, or the very string that was passed in where it should not
   * be or is there already.
   *
   * <p>Returning the argument is the fast path as much as it is the answer: every tool of every MCP
   * server a user has registered arrives here on every iteration of every request.
   *
   * <p>Left alone in four cases, each for its own reason. A schema that will not parse is a schema
   * this cannot safely rewrite, and losing a title beats losing the tool. A schema with no {@code
   * properties} object is a tool that takes nothing — and the {@code "{}"} stub the callback
   * resolver synthesizes for a name it could not place, which is not a tool at all. A tool
   * declaring {@code description} itself already answers the question, in the words its own author
   * chose. And an open schema, {@code additionalProperties: true}, is one whose arguments are a
   * payload rather than a parameter list: a field added there means something to whatever reads
   * that payload.
   */
  public static String inject(
      final String toolName, final String schema, final String parameterDescription) {
    if (Strings.isNullOrEmpty(schema)) {
      return schema;
    }
    final ObjectNode root;
    try {
      if (!(MAPPER.readTree(schema) instanceof ObjectNode object)) {
        return schema;
      }
      root = object;
    } catch (JacksonException e) {
      log.warn("Could not parse the input schema of '{}'; left as it is", toolName, e);
      return schema;
    }
    if (!(root.get(PROPERTIES) instanceof ObjectNode properties)) {
      return schema;
    }
    if (properties.has(NATIVE_FIELD) || properties.has(FIELD)) {
      return schema;
    }
    final var additional = root.get(ADDITIONAL_PROPERTIES);
    if (additional != null && additional.isBoolean() && additional.booleanValue()) {
      return schema;
    }
    // Jackson's object nodes keep insertion order and these two keys are added to rather than
    // replaced, so $schema, $defs, additionalProperties and every parameter the tool declares are
    // neither reordered nor rewritten.
    properties.putObject(FIELD).put(TYPE, STRING).put(NATIVE_FIELD, parameterDescription);
    require(root);
    return root.toPrettyString();
  }

  /**
   * Says the parameter has to be answered, adding the schema's list of required ones where it has
   * none yet.
   *
   * <p>A {@code required} that is there but is not a list is left exactly where it is rather than
   * replaced: a schema malformed in that way is not one this should be repairing, and all it costs
   * is a description the model may then leave out.
   */
  private static void require(final ObjectNode root) {
    if (root.get(REQUIRED) instanceof ArrayNode required) {
      required.add(FIELD);
    } else if (!root.has(REQUIRED)) {
      root.putArray(REQUIRED).add(FIELD);
    }
  }

  /**
   * The arguments as the tool should receive them: whatever the model wrote, less the one field it
   * was asked for on the runtime's behalf.
   *
   * <p>Anything that is not a JSON object is returned untouched. A callback whose input this cannot
   * read is one whose input this has no business rewriting, and the field could not have reached it
   * anyway — it is only ever offered as a property of an object schema.
   */
  public static String strip(final String toolInput) {
    if (toolInput == null || !toolInput.contains(FIELD)) {
      return toolInput;
    }
    try {
      if (MAPPER.readTree(toolInput) instanceof ObjectNode arguments
          && arguments.remove(FIELD) != null) {
        return MAPPER.writeValueAsString(arguments);
      }
    } catch (JacksonException e) {
      // Not JSON, so it has no argument to remove. The tool gets what the model wrote, which is
      // what it would have got had this never run.
      log.debug("Tool arguments are not JSON; left as they are");
    }
    return toolInput;
  }
}
