package me.kezhenxu94.springagent.integration.email.config;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.core.observing.EventIntakes;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import me.kezhenxu94.springagent.integration.email.MailObservationHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.integration.mail.inbound.ImapIdleChannelAdapter;

/**
 * That the two switches mean what they say, and that a misconfigured mailbox is found at startup.
 *
 * <p>Deliberately no test that enables the source with a reachable-looking host: the adapter is a
 * lifecycle bean and starting the context would start it dialling. What is worth pinning here is
 * everything up to that point — which beans exist, and which configurations refuse.
 */
class EmailAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EmailAutoConfiguration.class))
          .withBean(EventIntakes.class, () -> new EventIntakes(java.util.List.of()));

  private static EventsProperties events(final String... trustedActors) {
    return EventsProperties.builder()
        .enabled(true)
        .sources(
            java.util.Map.of(
                EmailProperties.SOURCE,
                EventsProperties.Source.builder()
                    .owner(EventsProperties.Owner.builder().userId("ou_agent").build())
                    .trustedActors(
                        trustedActors.length == 0 ? null : java.util.List.of(trustedActors))
                    .build()))
        .build();
  }

  @Test
  @DisplayName("nothing is registered until app.email.enabled says so")
  void shouldRegisterNothingByDefault() {
    runner
        .withBean(EventsProperties.class, () -> events(".+@example\\.com"))
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(ImapIdleChannelAdapter.class)
                    .doesNotHaveBean(MailObservationHandler.class));
  }

  @Test
  @DisplayName("nor while the events machinery that would make something of it is off")
  void shouldRegisterNothingWithoutTheEventsModule() {
    // No EventsProperties bean is what app.events.enabled=false looks like from here. Taking this
    // module and turning it on decides nothing on its own, which is the same arrangement the
    // webhook sources have.
    runner
        .withPropertyValues("app.email.enabled=true", "app.email.host=imap.example.com")
        .run(
            context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(ImapIdleChannelAdapter.class));
  }

  @Test
  @DisplayName("a mailbox that would accept anybody's mail stops the application starting")
  void shouldRefuseAMailboxWithNoTrustedActors() {
    runner
        .withBean(EventsProperties.class, () -> events())
        .withPropertyValues(
            "app.email.enabled=true",
            "app.email.host=imap.example.com",
            "app.email.authserv-id=mx.example.com")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("app.events.sources.email.trusted-actors"));
  }
}
