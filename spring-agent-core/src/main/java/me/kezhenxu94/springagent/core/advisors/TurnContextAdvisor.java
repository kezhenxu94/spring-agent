package me.kezhenxu94.springagent.core.advisors;

import java.util.ArrayList;
import java.util.regex.Pattern;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Puts what is true of this one message — who sent it, where, its id, its thread and parent, whom
 * it mentions, which homes the run reaches — at the top of the user's message, as a {@code
 * <system-reminder>} block, rather than in the system prompt.
 *
 * <p>The point is that the system prompt then carries nothing that changes from one message to the
 * next. Every provider caches a prompt by its prefix — OpenAI, DeepSeek, DashScope and Gemini
 * implicitly, Anthropic where it is asked to — and a system prompt naming this message's id differs
 * from the last one a few lines in, so nothing after that point, the whole conversation included,
 * was ever served from a cache. With the identity here, the system prompt and every earlier turn
 * are byte-for-byte what the previous request sent.
 *
 * <p><b>Merged into the user's message, not sent as a message of its own.</b> Two user messages in
 * a row is refused outright by some OpenAI-compatible endpoints — DeepSeek's reasoner among them —
 * and a second system message would be hoisted into the system prompt by Anthropic and Gemini,
 * which is exactly the prefix this exists to keep still.
 *
 * <p><b>And so it is remembered with the turn.</b> That is deliberate: the next request has to
 * replay this turn as it was sent, or its prefix stops matching at this message. Whatever shows a
 * remembered message to a person removes the block with {@link #strip} first.
 *
 * <p>Ahead of {@link ToolCallingAdvisor}, which is where {@code MemoryToolCallingAdvisor} reads the
 * message it saves, and so called once per turn rather than once per round of the loop.
 */
public final class TurnContextAdvisor implements BaseAdvisor {

  public static final int ORDER = ToolCallingAdvisor.DEFAULT_ORDER - 200;

  private static final String OPEN = "<system-reminder>";
  private static final String CLOSE = "</system-reminder>";

  /**
   * The tag as somebody might type it — case, spacing and all — so that text a person wrote cannot
   * pass for a block the runtime wrote. Only the block this class puts first is the runtime's.
   */
  private static final Pattern TYPED_TAG =
      Pattern.compile("<(\\s*/?\\s*)(system-reminder)", Pattern.CASE_INSENSITIVE);

  private final String block;

  /**
   * @param context the rendered context, without the tags
   */
  public TurnContextAdvisor(final String context) {
    this.block = OPEN + "\n" + context.strip() + "\n" + CLOSE;
  }

  @Override
  public ChatClientRequest before(final ChatClientRequest request, final AdvisorChain chain) {
    final var messages = new ArrayList<>(request.prompt().getInstructions());
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof UserMessage user) {
        messages.set(i, user.mutate().text(prepend(block, user.getText())).build());
        return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
      }
    }
    return request;
  }

  @Override
  public ChatClientResponse after(final ChatClientResponse response, final AdvisorChain chain) {
    return response;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  /** {@code text} after {@code block}, with any tag the text itself carries defused. */
  static String prepend(final String block, final String text) {
    return block + "\n\n" + defuse(text);
  }

  /**
   * {@code text} a person wrote, with any {@code <system-reminder>} in it escaped, so that it reads
   * as what they typed rather than as the runtime speaking.
   */
  public static String defuse(final String text) {
    return text == null ? "" : TYPED_TAG.matcher(text).replaceAll("&lt;$1$2");
  }

  /** The block {@code text} opens with, or empty where it opens with none. */
  public static String leading(final String text) {
    if (text == null || !text.startsWith(OPEN)) {
      return "";
    }
    final var end = text.indexOf(CLOSE);
    return end < 0 ? "" : text.substring(0, end + CLOSE.length());
  }

  /**
   * {@code text} with {@code addition} at the end of the block it opens with, or in a block of its
   * own ahead of it where it opens with none. What goes here is remembered with the turn and taken
   * off again by {@link #strip} wherever a person is shown the message.
   */
  public static String addToLeading(final String text, final String addition) {
    final var block = leading(text);
    if (block.isEmpty()) {
      return OPEN + "\n" + addition.strip() + "\n" + CLOSE + "\n\n" + (text == null ? "" : text);
    }
    final var body = block.substring(0, block.length() - CLOSE.length()).stripTrailing();
    return body + "\n\n" + addition.strip() + "\n" + CLOSE + text.substring(block.length());
  }

  /** {@code text} without the block it opens with: what the person actually said. */
  public static String strip(final String text) {
    final var block = leading(text);
    return block.isEmpty() ? text : text.substring(block.length()).stripLeading();
  }
}
