package me.kezhenxu94.springagent.events.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.observing.EventIntake;
import me.kezhenxu94.springagent.core.observing.EventIntakes;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import me.kezhenxu94.springagent.events.source.WebhookDelivery;
import me.kezhenxu94.springagent.events.source.WebhookSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The one HTTP endpoint this module adds, and the only part of it reachable by anybody at all.
 *
 * <p>Everything asserted here is about refusing things. The path is permitted in the application's
 * filter chain because a webhook has no session to log in with, so this controller and the source
 * behind it are the whole of the authentication — and the interesting cases are the ones where a
 * caller learns something, or gets further than it should.
 */
class WebhookControllerTest {

  private final Recording intake = new Recording();

  /**
   * Stands in for a real source: it accepts one secret and reports one observation per delivery.
   */
  private static final class StubSource implements WebhookSource {
    private final String name;
    private boolean threwOnVerify;
    private RuntimeException verifyFailure;
    private RuntimeException readFailure;
    private boolean saysNothing;

    StubSource(final String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public boolean verify(final WebhookDelivery delivery, final String secret) {
      if (verifyFailure != null) {
        threwOnVerify = true;
        throw verifyFailure;
      }
      return secret != null && secret.equals(delivery.header("X-Secret"));
    }

    @Override
    public Optional<Observation> observation(final WebhookDelivery delivery) {
      if (readFailure != null) {
        throw readFailure;
      }
      if (saysNothing) {
        return Optional.empty();
      }
      return Optional.of(
          Observation.builder()
              .source(name)
              .deliveryId("d1")
              .kind("thing.happened")
              .correlationKey(name + ":1")
              .payloadJson(delivery.bodyAsText())
              .build());
    }
  }

  private static final class Recording implements EventIntake {
    private final List<Observation> observed = new ArrayList<>();
    private RuntimeException failure;

    @Override
    public void observe(final Observation observation) {
      observed.add(observation);
      if (failure != null) {
        throw failure;
      }
    }
  }

  private MockMvc mockMvc(final StubSource source, final EventsProperties properties) {
    return MockMvcBuilders.standaloneSetup(
            new WebhookController(List.of(source), properties, new EventIntakes(List.of(intake))))
        .build();
  }

  private static EventsProperties configured(final String sourceName, final String secret) {
    return EventsProperties.builder()
        .enabled(true)
        .sources(
            Map.of(
                sourceName,
                EventsProperties.Source.builder().secret(secret).ownerUserId("ou_bot").build()))
        .build();
  }

  @Test
  @DisplayName("an authentic delivery is recorded and answered with no content")
  void shouldAcceptAnAuthenticDelivery() throws Exception {
    final var mockMvc = mockMvc(new StubSource("github"), configured("github", "shh"));

    mockMvc
        .perform(
            post("/events/webhooks/github")
                .header("X-Secret", "shh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"opened\"}"))
        .andExpect(status().isNoContent());

    assertThat(intake.observed).hasSize(1);
    assertThat(intake.observed.getFirst().source()).isEqualTo("github");
  }

  @Test
  @DisplayName("nothing about what we made of it comes back")
  void shouldSayNothingAboutTheOutcome() throws Exception {
    // A sender can act on "it arrived" and on nothing else. What the agent decides happens minutes
    // later and is not a reply — telling the sender would also tell it what this deployment
    // watches.
    final var mockMvc = mockMvc(new StubSource("github"), configured("github", "shh"));

    final var response =
        mockMvc
            .perform(
                post("/events/webhooks/github")
                    .header("X-Secret", "shh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn()
            .getResponse();

    assertThat(response.getContentAsString()).isEmpty();
  }

  @Test
  @DisplayName("a delivery that does not authenticate is refused")
  void shouldRefuseABadSecret() throws Exception {
    final var mockMvc = mockMvc(new StubSource("github"), configured("github", "shh"));

    mockMvc
        .perform(
            post("/events/webhooks/github")
                .header("X-Secret", "guess")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());

    assertThat(intake.observed).isEmpty();
  }

  @Test
  @DisplayName("a source nobody configured is refused, and looks exactly like one that is unknown")
  void shouldRefuseAnUnconfiguredSource() throws Exception {
    // Same answer for both on purpose: a caller learning which of the two it is learns what this
    // deployment watches.
    final var unconfigured = EventsProperties.builder().enabled(true).build();
    final var mockMvc = mockMvc(new StubSource("github"), unconfigured);

    mockMvc
        .perform(
            post("/events/webhooks/github")
                .header("X-Secret", "shh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(
            post("/events/webhooks/nosuchthing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());

    assertThat(intake.observed).isEmpty();
  }

  @Test
  @DisplayName("a source with no secret configured accepts nothing")
  void shouldRefuseEverythingWhenNoSecretIsSet() throws Exception {
    // The state a half-finished deployment is in, and the one where an open endpoint would be
    // worst.
    final var mockMvc = mockMvc(new StubSource("github"), configured("github", null));

    mockMvc
        .perform(
            post("/events/webhooks/github")
                .header("X-Secret", "anything")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("an oversized body is refused before anything parses it")
  void shouldRefuseAnOversizedBody() throws Exception {
    final var tiny =
        EventsProperties.builder()
            .enabled(true)
            .maxBodySize(org.springframework.util.unit.DataSize.ofBytes(16))
            .sources(Map.of("github", EventsProperties.Source.builder().secret("shh").build()))
            .build();
    final var mockMvc = mockMvc(new StubSource("github"), tiny);

    mockMvc
        .perform(
            post("/events/webhooks/github")
                .header("X-Secret", "shh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(64).getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isContentTooLarge());

    assertThat(intake.observed).isEmpty();
  }

  @Test
  @DisplayName("a delivery is one observation, however much the sender put in it")
  void shouldRecordOneObservationPerDelivery() throws Exception {
    // The sender already decided what belongs together — Grafana groups by the contact point's
    // group_by and posts the group. Taking it apart would ask the agent for an opinion on each of
    // thirty things it was told are one, and would store the delivery once per alert.
    final var source = new StubSource("grafana");
    final var mockMvc = mockMvc(source, configured("grafana", "shh"));

    mockMvc
        .perform(
            post("/events/webhooks/grafana")
                .header("X-Secret", "shh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alerts\":[{},{},{}]}"))
        .andExpect(status().isNoContent());

    assertThat(intake.observed).hasSize(1);
  }

  @Test
  @DisplayName("a delivery that says nothing worth recording is still answered")
  void shouldAcceptADeliveryThatSaysNothing() throws Exception {
    // A ping, a test button, an empty batch. Not an error, and not something to make a sender
    // retry.
    final var source = new StubSource("github");
    source.saysNothing = true;
    final var mockMvc = mockMvc(source, configured("github", "shh"));

    mockMvc
        .perform(
            post("/events/webhooks/github")
                .header("X-Secret", "shh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNoContent());

    assertThat(intake.observed).isEmpty();
  }

  @Test
  @DisplayName("an observation nothing could record still leaves the sender answered")
  void shouldAnswerWhenTheObservationCannotBeRecorded() throws Exception {
    // Isolating a failing consumer is EventIntakes' job, and this asserts the controller leans on
    // it: a 500 here would have the sender redeliver something that was read perfectly well.
    final var source = new StubSource("grafana");
    intake.failure = new IllegalStateException("the database is away");
    final var mockMvc = mockMvc(source, configured("grafana", "shh"));

    mockMvc
        .perform(
            post("/events/webhooks/grafana")
                .header("X-Secret", "shh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alerts\":[{}]}"))
        .andExpect(status().isNoContent());

    assertThat(intake.observed).hasSize(1);
  }

  @Test
  @DisplayName(
      "a source that throws while verifying refuses the delivery rather than answering 500")
  void shouldTreatAThrowingVerifyAsARefusal() throws Exception {
    // verify is documented as never throwing, and the real ones are written that way — but this is
    // the method unauthenticated traffic reaches, and a 500 with a stack trace in the log is a more
    // useful reply than a forger deserves.
    final var source = new StubSource("github");
    source.verifyFailure = new IllegalArgumentException("odd number of hex digits");
    final var mockMvc = mockMvc(source, configured("github", "shh"));

    mockMvc
        .perform(
            post("/events/webhooks/github")
                .header("X-Secret", "shh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());

    assertThat(source.threwOnVerify).isTrue();
    assertThat(intake.observed).isEmpty();
  }

  @Test
  @DisplayName(
      "an authentic delivery we cannot read is still answered, since retrying will not help")
  void shouldAcceptADeliveryItCannotRead() throws Exception {
    final var source = new StubSource("github");
    source.readFailure = new IllegalStateException("unknown payload shape");
    final var mockMvc = mockMvc(source, configured("github", "shh"));

    mockMvc
        .perform(
            post("/events/webhooks/github")
                .header("X-Secret", "shh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNoContent());

    assertThat(intake.observed).isEmpty();
  }

  @Test
  @DisplayName("an empty body is a refusal, not a crash")
  void shouldHandleAnEmptyBody() throws Exception {
    final var mockMvc = mockMvc(new StubSource("github"), configured("github", "shh"));

    mockMvc
        .perform(post("/events/webhooks/github").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("two sources claiming one name is a startup failure, not a coin toss")
  void shouldRefuseTwoSourcesWithTheSameName() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new WebhookController(
                    List.of(new StubSource("github"), new StubSource("github")),
                    configured("github", "shh"),
                    new EventIntakes(List.of(intake))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("github");
  }
}
