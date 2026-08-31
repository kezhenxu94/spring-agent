package me.kezhenxu94.springagent.integration.email.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one source that refuses to run on the permissive default every other source takes.
 *
 * <p>Worth its own test because the asymmetry is deliberate and looks, to somebody reading the code
 * later, like an inconsistency to tidy away. The permissive default exists so that a deployment
 * upgrading into trusted actors does not silently stop triaging a repository webhook it was already
 * triaging. Nobody upgrades into a mailbox: an address is reachable by whoever learns it, so
 * "everybody" here means the internet.
 */
class EmailSourceCheckTest {

  private static EmailProperties email() {
    return EmailProperties.builder()
        .enabled(true)
        .host("imap.example.com")
        .authservId("mx.example.com")
        .username("agent@example.com")
        .password("x")
        .build();
  }

  private static EventsProperties events(final EventsProperties.Source source) {
    return EventsProperties.builder()
        .enabled(true)
        .sources(source == null ? Map.of() : Map.of(EmailProperties.SOURCE, source))
        .build();
  }

  private static void check(final EmailProperties email, final EventsProperties events) {
    new EmailAutoConfiguration().emailSourceCheck(email, events);
  }

  @Test
  @DisplayName("a fully configured source starts")
  void shouldStartWhenConfigured() {
    // The control. Without it a check that threw on everything would pass every test below.
    assertThatCode(
            () ->
                check(
                    email(),
                    events(
                        EventsProperties.Source.builder()
                            .owner(EventsProperties.Owner.builder().userId("ou_agent").build())
                            .trustedActors(List.of(".+@apache\\.org"))
                            .build())))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a mailbox that would accept anybody's mail refuses to start")
  void shouldRefuseWithoutTrustedActors() {
    assertThatThrownBy(
            () ->
                check(
                    email(),
                    events(
                        EventsProperties.Source.builder()
                            .owner(EventsProperties.Owner.builder().userId("ou_agent").build())
                            .build())))
        .isInstanceOf(IllegalStateException.class)
        // Named in full, with an example: whoever hits this is configuring the feature for the
        // first time and the message is the only documentation in front of them.
        .hasMessageContaining("app.events.sources.email.trusted-actors")
        .hasMessageContaining("example");
  }

  @Test
  @DisplayName("no way to authenticate anybody refuses to start, rather than dropping everything")
  void shouldRefuseWithoutAnAuthservId() {
    // Without it every message is dropped for having no actor, and the symptom is a mailbox filling
    // up while the agent says nothing — indistinguishable from nobody having written.
    assertThatThrownBy(
            () ->
                check(
                    EmailProperties.builder().enabled(true).host("imap.example.com").build(),
                    events(
                        EventsProperties.Source.builder()
                            .owner(EventsProperties.Owner.builder().userId("ou_agent").build())
                            .trustedActors(List.of(".*"))
                            .build())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.email.authserv-id");
  }

  @Test
  @DisplayName("a mailbox nobody configured a policy for refuses to start")
  void shouldRefuseWithoutASource() {
    assertThatThrownBy(() -> check(email(), events(null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.events.sources.email");
  }

  @Test
  @DisplayName("no mailbox to watch refuses to start")
  void shouldRefuseWithoutAHost() {
    assertThatThrownBy(
            () ->
                check(
                    EmailProperties.builder().enabled(true).authservId("mx.example.com").build(),
                    events(
                        EventsProperties.Source.builder()
                            .owner(EventsProperties.Owner.builder().userId("ou_agent").build())
                            .trustedActors(List.of(".*"))
                            .build())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.email.host");
  }

  @Test
  @DisplayName("the store uri carries no credentials, whatever the password contains")
  void shouldKeepCredentialsOutOfTheUri() {
    // They go through the authenticator instead. A password with an @ or a / in it would either
    // fail to parse as a uri or, worse, parse into a different host.
    final var uri =
        EmailProperties.builder()
            .host("imap.example.com")
            .username("agent@example.com")
            .password("p@ss/word")
            .build()
            .storeUri();

    assertThat(uri).isEqualTo("imaps://imap.example.com:993/INBOX");
  }
}
