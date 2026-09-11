package me.kezhenxu94.springagent.core.tools.interceptors;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps a tool response in the shape Spring AI promises it is: JSON.
 *
 * <p>Easy to miss, because a tool method here returns an ordinary sentence and the promise is kept
 * for it by somebody else — {@code DefaultToolCallResultConverter} runs {@code toJson} over every
 * return value, so {@code the file was written} reaches the wire as a quoted JSON string. What is
 * left are the answers <em>no tool produced</em>, and there are more of those than is obvious:
 *
 * <ul>
 *   <li>this project's own — a refusal from an interceptor, arguments the model did not finish
 *       writing, a result {@code LargeResponseInterceptor} replaced with a note about where it was
 *       saved;
 *   <li>Spring AI's own, inside {@code DefaultToolCallingManager} and so beyond any callback: a
 *       thrown tool becomes {@code toolExecutionExceptionProcessor.process(ex)}, and a run over the
 *       tool-call limit becomes {@code limitBreach.message()}. Both are prose.
 * </ul>
 *
 * <p>Nothing noticed while every deployment spoke the OpenAI protocol, which copies the string into
 * the tool message verbatim and never looks at it. Gemini looks: a {@code functionResponse} carries
 * a {@code Map}, so {@code GoogleGenAiChatModel} parses what it is given and throws {@code Failed
 * to parse JSON} — reaching a user as a run dying on {@code Stream processing failed}, naming
 * neither the tool nor what answered for it.
 *
 * <p>Applied at two points, because the two sets of producers are reachable from different places:
 * {@code InterceptingToolCallback} for its own substitutions, and {@code
 * InterceptingToolCallingManager} for everything a turn produced, which is the backstop that also
 * catches Spring AI's. It is idempotent, so a result passing both is untouched by the second.
 */
final class ToolResultJson {

  /** Answers are the model's to read, not a document: no application configuration reaches this. */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private ToolResultJson() {}

  /** {@code result} if it already is JSON, and {@code result} encoded as a JSON string if not. */
  static String asJson(final String result) {
    if (result == null) {
      return "null";
    }
    try {
      // The ordinary case: an uninterrupted call returns what the converter produced. Passed
      // through rather than re-encoded, which would reach the model as a quoted blob of text where
      // an object was expected.
      MAPPER.readTree(result);
      return result;
    } catch (JacksonException e) {
      return MAPPER.writeValueAsString(result);
    }
  }
}
