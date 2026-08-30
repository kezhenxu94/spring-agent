package me.kezhenxu94.springagent.integration.email;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One {@code Authentication-Results} header, as RFC 8601 defines it, parsed into what this module
 * asks of it: did DKIM verify, and for whose domain.
 *
 * <p><b>This is the whole of the trust in an email source, so it is worth being exact about where
 * that trust comes from.</b> Nothing here verifies a signature. What it does is read the verdict of
 * whichever server stamped this header, and that verdict is worth something only under an
 * assumption the deployment has to arrange and this code cannot check: that the mailbox is fed by a
 * server the deployment controls, that the server performs DKIM verification, and that it strips
 * inbound headers bearing its own {@code authserv-id} before adding its own. RFC 8601 §5 says an
 * MDA should do the last of those; if yours does not, everything below is decoration, because a
 * message can arrive with any header its author cared to type.
 *
 * <p>Verifying DKIM here instead was considered and rejected. It means canonicalisation, a DNS
 * lookup on the receive path with its own caching and failure modes, and either a Java DKIM library
 * — the available ones are thin and largely unmaintained — or cryptographic code of our own. That
 * is a larger surface of our own making than the one it closes, given that the assumption above is
 * one every mail deployment already relies on for spam filtering.
 *
 * <p><b>Only the topmost matching header is ever read.</b> Headers accumulate downward as a message
 * is handled, so the first one bearing the configured {@code authserv-id} is the one the last hop
 * added — ours. An attacker may write as many as they like; theirs land below and are never looked
 * at. This is why {@link #firstIn} takes the whole array and returns one, rather than searching for
 * the first that happens to say {@code pass}: a search would find the attacker's.
 *
 * @param authservId who is making the claim, as they named themselves
 * @param results one entry per method the server reported on, in the order it reported them
 */
public record AuthenticationResults(String authservId, List<Result> results) {

  /**
   * One method's verdict and whatever it said about it.
   *
   * @param method {@code "dkim"}, {@code "spf"}, {@code "dmarc"}
   * @param result {@code "pass"}, {@code "fail"}, {@code "none"}, lowercased
   * @param properties the {@code ptype.property=pvalue} pairs, keyed {@code "header.d"} and such
   */
  public record Result(String method, String result, Map<String, String> properties) {

    /** The domain a DKIM signature was made for, or null where this result names none. */
    public String signingDomain() {
      return properties.get("header.d");
    }
  }

  /**
   * The topmost header claimed by {@code authservId}, or empty where there is none.
   *
   * @param headers every {@code Authentication-Results} header on the message, in the order they
   *     appear on it — which is what {@code MimeMessage#getHeader(String)} returns
   * @param authservId the identity this deployment's own mail server signs its verdicts with,
   *     configured as {@code app.email.authserv-id}
   */
  public static Optional<AuthenticationResults> firstIn(
      final String[] headers, final String authservId) {
    if (headers == null || authservId == null || authservId.isBlank()) {
      return Optional.empty();
    }
    for (final var header : headers) {
      final var parsed = parse(header);
      if (parsed.isPresent() && parsed.get().authservId().equalsIgnoreCase(authservId.trim())) {
        // The first match and no other. See the note on this class about why this is not a search.
        return parsed;
      }
    }
    return Optional.empty();
  }

  /**
   * The names every readable header on the message claimed, in order.
   *
   * <p>Only for saying, when none of them matched, which ones were there. That is the whole of what
   * somebody needs to fix {@code app.email.authserv-id}, and without it a mismatch is a mailbox
   * that reports nothing with no indication of why.
   *
   * <p>Nothing decides anything on this. It is a list of names an attacker can contribute to, which
   * is fine for a log line naming what was seen and would not be fine for anything else.
   */
  public static List<String> identitiesIn(final String[] headers) {
    if (headers == null) {
      return List.of();
    }
    return java.util.Arrays.stream(headers)
        .map(AuthenticationResults::parse)
        .flatMap(java.util.Optional::stream)
        .map(AuthenticationResults::authservId)
        .toList();
  }

  /** Every DKIM result this header reported as having verified. */
  public List<Result> dkimPasses() {
    return results.stream()
        .filter(result -> "dkim".equals(result.method()) && "pass".equals(result.result()))
        .toList();
  }

  private static Optional<AuthenticationResults> parse(final String header) {
    if (header == null || header.isBlank()) {
      return Optional.empty();
    }
    final var parts = uncommented(header).split(";", -1);
    // The authserv-id, optionally followed by a version number this does not care about.
    final var identity = parts[0].trim().split("\\s+")[0];
    if (identity.isEmpty()) {
      return Optional.empty();
    }
    final var results = new ArrayList<Result>();
    for (var i = 1; i < parts.length; i++) {
      result(parts[i]).ifPresent(results::add);
    }
    return Optional.of(new AuthenticationResults(identity, List.copyOf(results)));
  }

  private static Optional<Result> result(final String text) {
    final var tokens = text.trim().split("\\s+");
    final var verdict = tokens[0].split("=", 2);
    if (verdict.length != 2 || verdict[0].isBlank() || verdict[1].isBlank()) {
      // "none", a stray semicolon, a method with no result. Not an error and not a pass.
      return Optional.empty();
    }
    final var properties = new LinkedHashMap<String, String>();
    for (var i = 1; i < tokens.length; i++) {
      final var pair = tokens[i].split("=", 2);
      if (pair.length == 2) {
        properties.put(
            pair[0].trim().toLowerCase(Locale.ROOT), unquoted(pair[1]).toLowerCase(Locale.ROOT));
      }
    }
    return Optional.of(
        new Result(
            verdict[0].trim().toLowerCase(Locale.ROOT),
            // "pass/policy.foo" and "pass (reason)" both occur; the verdict is up to the slash.
            verdict[1].trim().split("/")[0].toLowerCase(Locale.ROOT),
            Map.copyOf(properties)));
  }

  /**
   * The header with its comments removed, since a comment may appear between any two tokens.
   *
   * <p>Removed rather than ignored in place, and this matters more than it looks: {@code dkim=fail
   * (dkim=pass)} is a legal header, and anything scanning the raw text for a verdict would find the
   * one inside the parentheses. Comments nest, so the depth is counted rather than matched.
   *
   * <p>Quoted strings are tracked for the same reason in the other direction — a parenthesis inside
   * {@code header.d="a(b"} opens nothing — and a quote inside a comment is not a quote.
   */
  private static String uncommented(final String header) {
    final var out = new StringBuilder(header.length());
    var depth = 0;
    var quoted = false;
    for (var i = 0; i < header.length(); i++) {
      final var c = header.charAt(i);
      if (quoted) {
        if (c == '\\' && i + 1 < header.length()) {
          out.append(c).append(header.charAt(++i));
          continue;
        }
        if (c == '"') {
          quoted = false;
        }
        out.append(c);
      } else if (depth > 0) {
        if (c == '\\') {
          i++;
        } else if (c == '(') {
          depth++;
        } else if (c == ')') {
          depth--;
        }
      } else if (c == '(') {
        depth++;
        // A comment stands where whitespace would, so that "dkim=pass(x)header.d=y" does not become
        // one unreadable token.
        out.append(' ');
      } else {
        if (c == '"') {
          quoted = true;
        }
        out.append(c);
      }
    }
    return out.toString();
  }

  private static String unquoted(final String value) {
    final var trimmed = value.trim();
    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed;
  }
}
