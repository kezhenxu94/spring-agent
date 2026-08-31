package me.kezhenxu94.springagent.appweb.config;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What this application needs that the runtime does not already have a knob for.
 *
 * @param journal how much of a run's detail is kept for a browser that comes back to it
 * @param question how long a question the agent asked stays answerable
 * @param auth who is allowed in
 * @param locale the language to fall back to when a browser asks for one nothing is written in.
 *     Null means English, which is what every bundle here has a translation for
 */
@ConfigurationProperties(prefix = "app.web")
public record WebProperties(Journal journal, Question question, Auth auth, Locale locale) {

  public WebProperties {
    journal = journal == null ? new Journal(null, null) : journal;
    question = question == null ? new Question(null) : question;
    auth = auth == null ? new Auth(null, null) : auth;
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
