package me.kezhenxu94.springagent.integration.slack.handler;

import com.google.common.base.Strings;
import com.slack.api.methods.MethodsClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Who a {@code <@U123>} in a message is, in words.
 *
 * <p>Slack sends a mention as an id and nothing else, so a message that reads "ask @alice about it"
 * arrives as "ask &lt;@U123ABC&gt; about it" — which tells the model nothing at all, and which it
 * cannot write back either without knowing whose id that was.
 *
 * <p>Cached, and that is the point of the class rather than an optimisation on top of it. A message
 * naming five people is five {@code users.info} calls, on the thread Slack is waiting for an
 * acknowledgement on, and the same handful of people are named over and over in a channel. The
 * cache is unbounded in principle and bounded in practice by how many people are in the workspace;
 * a display name that changes is worth less than a call per mention.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackUserNames {

  private static final Pattern MENTION = Pattern.compile("<@([A-Z0-9]+)(\\|[^>]*)?>");

  private final MethodsClient slack;

  private final ConcurrentMap<String, String> names = new ConcurrentHashMap<>();

  /**
   * The display name for {@code userId}, or the id itself where Slack will not say.
   *
   * <p>Falling back to the id rather than to a placeholder: it is what the model needs to write the
   * mention back, and an id in the prompt is worse than a name but better than nothing.
   */
  public String nameOf(final String userId) {
    if (Strings.isNullOrEmpty(userId)) {
      return "";
    }
    return names.computeIfAbsent(
        userId,
        id -> {
          try {
            final var response = slack.usersInfo(r -> r.user(id));
            if (!response.isOk() || response.getUser() == null) {
              log.debug("Could not look up {}: {}", id, response.getError());
              return id;
            }
            final var user = response.getUser();
            final var profile = user.getProfile();
            if (profile != null && !Strings.isNullOrEmpty(profile.getDisplayName())) {
              return profile.getDisplayName();
            }
            if (profile != null && !Strings.isNullOrEmpty(profile.getRealName())) {
              return profile.getRealName();
            }
            return Strings.isNullOrEmpty(user.getName()) ? id : user.getName();
          } catch (Exception e) {
            log.debug("Could not look up {}", id, e);
            return id;
          }
        });
  }

  /** Rewrites every {@code <@U123>} in {@code text} to the name behind it. */
  public String resolve(final String text) {
    if (Strings.isNullOrEmpty(text)) {
      return Strings.nullToEmpty(text);
    }
    final var matcher = MENTION.matcher(text);
    final var out = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(
          out, java.util.regex.Matcher.quoteReplacement(nameOf(matcher.group(1))));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /**
   * Everybody named in {@code text}, as {@code Name (U123)} — the form the prompt's {@code
   * mentions} variable takes, and which carries the id because that is what the model has to write
   * to mention somebody back.
   *
   * <p>Ordered and de-duplicated: a message that names the same person twice should not say so
   * twice, and the order they were named in is the only order that means anything.
   */
  public String mentionsIn(final String text) {
    if (Strings.isNullOrEmpty(text)) {
      return "none";
    }
    final Map<String, String> found = new LinkedHashMap<>();
    final var matcher = MENTION.matcher(text);
    while (matcher.find()) {
      found.computeIfAbsent(matcher.group(1), this::nameOf);
    }
    if (found.isEmpty()) {
      return "none";
    }
    return found.entrySet().stream()
        .map(entry -> entry.getValue() + " (" + entry.getKey() + ")")
        .reduce((a, b) -> a + ", " + b)
        .orElse("none");
  }
}
