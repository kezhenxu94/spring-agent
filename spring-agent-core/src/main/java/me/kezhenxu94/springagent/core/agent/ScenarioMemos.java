package me.kezhenxu94.springagent.core.agent;

import com.google.common.base.Strings;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Which scenario a person asked for, read out of what they typed.
 *
 * <p>A surface hardcodes {@link BuiltInScenarios#CHAT} on every request it builds, which is right
 * until somebody wants one turn answered differently — against the knowledge base alone, say. This
 * is how they say so: a memo, {@code /kb}, somewhere in the message. The scenario declares its own
 * words in {@link AgentScenario#memoNames()}, so a scenario nobody should be able to summon simply
 * declares none, and there is no second list anywhere saying which ones a person may reach.
 *
 * <p><b>A memo is recognised anywhere in the message, not only at the front.</b> That is not
 * convenience: in a Feishu or Slack group the bot has to be mentioned, so what arrives is
 * {@code @_user_1 /kb what is this}, and a rule about the first characters would mean memos never
 * worked in the place most of those conversations happen. The cost is the wider net — a message
 * that talks <i>about</i> {@code /kb} selects it — which is the bargain {@code /config} declined
 * for itself, and it is declined there because that command takes no arguments and this one is
 * nothing but arguments.
 *
 * <p>What narrows it back down is that a memo is a whole token: preceded by the start of the
 * message or by whitespace, and ending at something that is not part of a word. So {@code
 * /kb/notes/2024} and {@code https://wiki/kb} are paths and not memos, which is most of what would
 * otherwise go wrong, while {@code what do we do when a deployment failed? /kb, tell me something}
 * is one — see {@link #MEMO} for exactly where a memo is allowed to end.
 *
 * <p>The first memo in the message wins and that one occurrence is removed from the prompt. A
 * {@code /word} that is nobody's memo is left exactly where it is and reaches the model as it does
 * today — the alternative, answering "no such scenario", would swallow every message that happens
 * to open with a slash.
 */
@Slf4j
@Component
public class ScenarioMemos {

  /**
   * A slash-word standing on its own.
   *
   * <p>The lookbehind is what keeps it a token rather than a suffix: {@code x/kb} and {@code
   * https://wiki/kb} are not memos, because what precedes the slash is not whitespace. The word
   * itself may not contain a slash or a dot, so {@code /kb/notes/2024} is a path.
   *
   * <p>What may follow is looser, because a memo is typed mid-sentence. It ends at anything that is
   * not a word character — {@code /kb, tell me something} and {@code /kb?} are both the memo — and
   * a comma, semicolon or colon immediately after it is eaten with it, since that punctuation
   * belongs to the interjected word rather than to the sentence around it. A dot is kept where it
   * ends the sentence and refused where it does not, so {@code /kb.} is a memo and {@code /kb.md}
   * is a filename.
   */
  private static final Pattern MEMO =
      Pattern.compile("(?<=^|\\s)/([A-Za-z0-9_-]+)(?:[,;:]|(?![A-Za-z0-9_/-])(?!\\.\\S))");

  /** Lower-cased memo to the scenario that claimed it. */
  private final Map<String, AgentScenario> byMemo;

  /**
   * @param scenarios every {@link AgentScenario} published as a bean, which is how a consumer's own
   *     scenario becomes selectable — declaring the bean is the whole of it. The built-in ones are
   *     an enum and no bean, so they are added here by hand.
   */
  @Autowired
  public ScenarioMemos(final ObjectProvider<AgentScenario> scenarios) {
    this(scenarios.orderedStream().toList());
  }

  /**
   * The built-in scenarios plus {@code scenarios}, for a caller assembling this itself rather than
   * from a context — a test, or an embedder with no {@link AgentScenario} beans to find.
   */
  public ScenarioMemos(final Collection<? extends AgentScenario> scenarios) {
    final var memos = new HashMap<String, AgentScenario>();
    for (final var scenario : BuiltInScenarios.values()) {
      register(memos, scenario);
    }
    scenarios.forEach(scenario -> register(memos, scenario));
    this.byMemo = Map.copyOf(memos);
    if (!memos.isEmpty()) {
      log.info("Scenarios selectable by memo: {}", memos.keySet());
    }
  }

  /**
   * Refuses a context where two scenarios answer to the same word.
   *
   * <p>At startup rather than at the first message, and fatally rather than by picking one: the
   * losing scenario would otherwise be unreachable with nothing anywhere saying why, and which of
   * the two lost would depend on bean registration order.
   */
  private static void register(
      final Map<String, AgentScenario> memos, final AgentScenario scenario) {
    for (final var name : scenario.memoNames()) {
      if (Strings.isNullOrEmpty(name)) {
        continue;
      }
      final var memo = name.toLowerCase(Locale.ROOT);
      final var owner = memos.putIfAbsent(memo, scenario);
      if (owner != null && owner != scenario) {
        throw new IllegalStateException(
            "Two scenarios are both selected by '/"
                + memo
                + "': "
                + owner.getClass().getName()
                + " and "
                + scenario.getClass().getName()
                + ". Rename one of their memoNames().");
      }
    }
  }

  /**
   * The scenario a message asked for and the message with that memo taken out of it.
   *
   * @param scenario what to run as, which is {@code fallback} where the message named nobody
   * @param text what to send the model, with a recognised memo removed and the result trimmed;
   *     untouched otherwise
   */
  public record Chosen(AgentScenario scenario, String text) {}

  /** What {@code text} asked for, falling back to {@code fallback} where it asked for nothing. */
  public Chosen parse(final String text, final AgentScenario fallback) {
    if (Strings.isNullOrEmpty(text) || byMemo.isEmpty()) {
      return new Chosen(fallback, text);
    }
    final var matcher = MEMO.matcher(text);
    while (matcher.find()) {
      final var scenario = byMemo.get(matcher.group(1).toLowerCase(Locale.ROOT));
      if (scenario != null) {
        return new Chosen(scenario, cut(text, matcher));
      }
    }
    return new Chosen(fallback, text);
  }

  /**
   * {@code text} with {@code scenario}'s memo taken out of it, for a surface that decided which
   * scenario to run as before the prompt text it will send existed.
   *
   * <p>Feishu and Slack are both such surfaces: the text is assembled in a supplier, off the event
   * thread, because a message carrying an attachment has to download it first, and the platform
   * resends anything it is still waiting on. So the memo is read from what the person typed and
   * removed from the assembled text later. A no-op where the memo is not in it — which is every
   * message whose text came out of a file rather than a keyboard.
   */
  public String strip(final String text, final AgentScenario scenario) {
    if (Strings.isNullOrEmpty(text) || scenario == null || scenario.memoNames().isEmpty()) {
      return text;
    }
    final var matcher = MEMO.matcher(text);
    while (matcher.find()) {
      if (scenario == byMemo.get(matcher.group(1).toLowerCase(Locale.ROOT))) {
        return cut(text, matcher);
      }
    }
    return text;
  }

  /** Whether {@code word}, without its slash, selects a scenario. */
  public Optional<AgentScenario> named(final String word) {
    return Strings.isNullOrEmpty(word)
        ? Optional.empty()
        : Optional.ofNullable(byMemo.get(word.toLowerCase(Locale.ROOT)));
  }

  /**
   * The memo the matcher is sitting on, taken out of the text.
   *
   * <p>One adjacent run of spaces goes with it — the one after it, or the one before it where the
   * memo ended the line — so that removing a word from the middle of a sentence does not leave a
   * double space behind. Only spaces and tabs, never a line break: what Feishu hands the agent is
   * the quoted parent message, a newline, and then what was typed, so a rule that rejoined the two
   * sides with a space would run the question onto the end of the quotation it is about.
   */
  private static String cut(final String text, final Matcher matcher) {
    var before = text.substring(0, matcher.start());
    var after = text.substring(matcher.end());
    final var trimmed = stripLeadingSpaces(after);
    if (trimmed.length() < after.length()) {
      after = trimmed;
    } else {
      before = stripTrailingSpaces(before);
    }
    return (before + after).strip();
  }

  private static String stripLeadingSpaces(final String text) {
    var i = 0;
    while (i < text.length() && isSpaceOrTab(text.charAt(i))) {
      i++;
    }
    return text.substring(i);
  }

  private static String stripTrailingSpaces(final String text) {
    var i = text.length();
    while (i > 0 && isSpaceOrTab(text.charAt(i - 1))) {
      i--;
    }
    return text.substring(0, i);
  }

  private static boolean isSpaceOrTab(final char c) {
    return c == ' ' || c == '\t';
  }
}
