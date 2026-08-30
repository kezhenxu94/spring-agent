package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.events.config.EventsProperties;
import me.kezhenxu94.springagent.integration.email.config.EmailProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * That the switches in {@code application-local.yaml} are switches, and that they reach something.
 *
 * <p>Worth a test rather than a read-through, because both ways this file can be wrong are silent.
 * A block whose {@code on-profile} never matches is not an error — it is simply absent, and the
 * feature it was meant to turn on stays off with nothing said. A block that matches when it should
 * not is worse and just as quiet. Neither shows up in a build, and both look from the outside like
 * the feature being broken.
 *
 * <p>Properties are bound rather than the yaml parsed, so this also catches a block landing at a
 * nesting level Boot ignores in silence — the reasoning {@code DockerShellDefaultsTest} gives for
 * doing the same in the browser surface.
 */
class LocalProfilesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({EmailProperties.class, EventsProperties.class})
  static class Bindings {}

  private ApplicationContextRunner runner(final String... profiles) {
    return new ApplicationContextRunner()
        // The application's own configuration files, which an ApplicationContextRunner does not
        // load on its own — and they are the whole subject here.
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.profiles.active=" + String.join(",", profiles))
        .withUserConfiguration(Bindings.class);
  }

  @Test
  @DisplayName("local on its own turns nothing on")
  void shouldChangeNothingUnderLocalAlone() {
    // The property this file is loaded by is not the property any of it is gated on. Somebody
    // running locally should not find themselves connecting to a mailbox because of it.
    runner("local")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(EmailProperties.class).enabled()).isFalse();
              assertThat(context.getBean(EventsProperties.class).enabled()).isFalse();
            });
  }

  @Test
  @DisplayName("and the email profile turns on both halves the source needs")
  void shouldEnableTheMailboxAndTheMachineryTogether() {
    // Both, because either alone does nothing: app.email.enabled puts the reader there and
    // app.events.enabled puts there the thing that makes something of what it reads. Setting one
    // and forgetting the other is the mistake this block exists to prevent.
    runner("local", "integration-email")
        .run(
            context -> {
              final var email = context.getBean(EmailProperties.class);
              assertThat(email.enabled()).isTrue();
              // Only what is true of any provider. Nothing here names one — a host could be
              // copied from somebody's documentation, but the authserv-id is whatever their
              // server actually writes, and a plausible-looking default for it fails silently
              // rather than loudly.
              assertThat(email.port()).isEqualTo(EmailProperties.DEFAULT_PORT);
              assertThat(email.folder()).isEqualTo("INBOX");
              assertThat(email.host()).isEmpty();
              assertThat(email.authservId()).isEmpty();
              assertThat(email.username()).isEmpty();
              assertThat(email.password()).isEmpty();

              final var events = context.getBean(EventsProperties.class);
              assertThat(events.enabled()).isTrue();
              assertThat(events.sources()).containsKey(EmailProperties.SOURCE);

              // The loggers along the path, on without being asked for. Asserted because a
              // logging block is as easy to indent wrong as any other, and one that Boot ignored
              // in silence would leave somebody troubleshooting the feature with the very output
              // this profile exists to give them.
              final var environment = context.getEnvironment();
              assertThat(
                      environment.getProperty(
                          "logging.level.me.kezhenxu94.springagent.integration.email"))
                  .isEqualTo("DEBUG");
              assertThat(
                      environment.getProperty("logging.level.org.springframework.integration.mail"))
                  .isEqualTo("DEBUG");
              assertThat(environment.getProperty("logging.level.me.kezhenxu94.springagent.events"))
                  .isEqualTo("DEBUG");
            });
  }

  @Test
  @DisplayName("with nothing said about who may write, the source resolves to trusting nobody")
  void shouldLeaveTrustedActorsUnsetUntilSomebodySaysSo() {
    // Which is what makes the application refuse to start rather than watch a mailbox on anybody's
    // behalf. Pinned here because the mechanism is indirect: an unset environment variable binds to
    // an empty list, and EventsProperties turns an empty list into nothing said.
    runner("local", "integration-email")
        .run(
            context ->
                assertThat(
                        context
                            .getBean(EventsProperties.class)
                            .policyFor(EmailProperties.SOURCE)
                            .orElseThrow()
                            .trustedActors())
                    .isNull());
  }

  @Test
  @DisplayName("and takes them from the environment when there is something to take")
  void shouldReadTrustedActorsFromTheEnvironment() {
    runner("local", "integration-email")
        .withPropertyValues("EMAIL_TRUSTED_ACTORS=.+@example\\.com,release@apache\\.org")
        .run(
            context ->
                assertThat(
                        context
                            .getBean(EventsProperties.class)
                            .policyFor(EmailProperties.SOURCE)
                            .orElseThrow()
                            .trustedActors())
                    .containsExactly(".+@example\\.com", "release@apache\\.org"));
  }
}
