package me.kezhenxu94.springagent.integration.websocket.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What this application needs that the runtime does not already have a knob for.
 *
 * @param journal how much of a run's detail is kept for a browser that comes back to it
 * @param question how long a question the agent asked stays answerable
 * @param auth who is allowed in
 * @param title what this deployment calls itself: the browser tab, the sidebar brand and the
 *     heading a conversation title has not replaced yet. Somebody running the agent as their own
 *     product should not have to say Spring Agent in three places on somebody's screen. One value
 *     for every language, because a name a deployment chose for itself is not something this server
 *     can translate. Null where nobody set it, and then the name is a translated string like every
 *     other — {@code app-title}, resolved per reader — which is also how a name that <em>is</em>
 *     written differently in each language is given: a bundle of the consumer's own in {@code
 *     messages}, naming that one key
 * @param messages basenames of message bundles consulted before this module's own, in order. The
 *     extension point for a consumer embedding this module: naming one key in a bundle of their own
 *     overrides that string and leaves every other one alone, so they never take a copy of this
 *     module's bundle and then silently lose whatever is added to it later
 * @param locale the language to fall back to when a browser asks for one nothing is written in.
 *     Null means English, which is what every bundle here has a translation for
 */
@ConfigurationProperties(prefix = "app.web")
public record WebProperties(
    Journal journal,
    Question question,
    Auth auth,
    Locale locale,
    String title,
    List<String> messages) {

  public WebProperties {
    journal = journal == null ? new Journal(null, null) : journal;
    question = question == null ? new Question(null) : question;
    auth = auth == null ? new Auth(null, null) : auth;
    // Blank is null rather than a name: it means nobody renamed this deployment, which is what
    // sends the caller to the bundle for a name in the reader's own language.
    title = title == null || title.isBlank() ? null : title.trim();
    // A blank basename is dropped rather than passed on. It resolves to a bundle that does not
    // exist, and ResourceBundleMessageSource says nothing about one — the override would simply
    // never happen, which is the hardest kind of misconfiguration to see.
    messages =
        messages == null
            ? List.of()
            : messages.stream()
                .filter(it -> it != null && !it.isBlank())
                .map(String::trim)
                .toList();
  }

  /**
   * @param retention how long after a run ends its journal is kept. The case this exists for is a
   *     user reloading the page seconds after an answer and expecting to still see how it was
   *     reached; long enough for that, short enough that a busy server does not hold a day of runs
   * @param maxRuns how many journals are held at all, whatever their age. Memory, so it is bounded
   *     — see {@code RunJournals} for what eviction costs and what it cannot cost
   */
  public record Journal(Duration retention, Integer maxRuns) {
    public Journal {
      retention = retention == null ? Duration.ofMinutes(30) : retention;
      maxRuns = maxRuns == null ? 500 : maxRuns;
    }
  }

  /**
   * @param ttl how long an unanswered question stays answerable. Asking ends the turn here, so this
   *     is genuinely how long the user has — the run is over and only the form is waiting
   */
  public record Question(Duration ttl) {
    public Question {
      ttl = ttl == null ? Duration.ofHours(24) : ttl;
    }
  }

  /**
   * @param provider which OAuth2 registration the sign-in page redirects to — {@code feishu} or
   *     {@code slack}, matching a registration under {@code spring.security.oauth2.client}.
   *     Defaults to {@code feishu}, which is what this application has always used.
   * @param tenantId the workspace whose people may use this deployment: a Feishu tenant key or a
   *     Slack team id, depending on the provider above. Empty lets anybody who can complete the
   *     OAuth flow in, which for a public Feishu app means anybody at all — so it is deliberately a
   *     value a deployment has to set rather than one with a permissive default
   */
  public record Auth(String provider, String tenantId) {
    public Auth {
      provider = provider == null || provider.isBlank() ? "feishu" : provider.trim();
      tenantId = tenantId == null ? "" : tenantId.trim();
    }
  }
}
