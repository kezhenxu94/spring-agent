package me.kezhenxu94.springagent.core.observing;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Every {@link EventIntake} in the application, as one thing a transport can report to.
 *
 * <p>A transport should not have to know how many consumers there are, whether there are any, or
 * what happens when one of them fails — it saw something and says so. This is where all three
 * answers live, so that the Feishu integration and the webhook receiver are each one call and no
 * error handling.
 *
 * <p>Always a bean, even where nothing implements the SPI, which is what removes the {@code
 * ObjectProvider} and its null check from every caller. An application with no intake at all
 * observes nothing, and that is a supported configuration rather than a degraded one.
 *
 * <p>Failure is isolated per intake, the same arrangement {@code SpringAgent} uses when it notifies
 * response listeners. One consumer's database being away must not stop another from posting its
 * message, and neither must stop the transport from acknowledging the event it was handed — a
 * webhook that answers 500 gets redelivered, and a Feishu event that throws gets redelivered too.
 *
 * <p>Order is Spring's, and no intake may depend on it. They are independent consumers of the same
 * fact, not a pipeline.
 */
@Slf4j
@Component
public class EventIntakes {

  private final List<EventIntake> intakes;

  public EventIntakes(final List<EventIntake> intakes) {
    this.intakes = List.copyOf(intakes);
    if (this.intakes.isEmpty()) {
      log.debug("Nothing consumes observations; anything reported will be dropped");
    }
  }

  /** Hands {@code observation} to every intake, whatever any of them makes of it. */
  public void observe(final Observation observation) {
    for (final var intake : intakes) {
      try {
        intake.observe(observation);
      } catch (RuntimeException e) {
        log.error(
            "{} could not take the {} observation {}",
            intake.getClass().getSimpleName(),
            observation.source(),
            observation.deliveryId(),
            e);
      }
    }
  }

  /** How many consumers there are, for a caller that wants to skip the work of building one. */
  public boolean isEmpty() {
    return intakes.isEmpty();
  }
}
