package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import java.util.concurrent.atomic.AtomicReference;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.test.annotation.DirtiesContext;

/**
 * That the chat surface lets go of its connection before the agent starts waiting out the runs
 * already going.
 *
 * <p>The order of two shutdown listeners, and getting it wrong is a rolling update that loses
 * messages. {@link SpringAgent#onShutdown()} flips {@code accepting} and then waits for up to
 * {@code app.shutdown.in-flight-wait-timeout} — half an hour by default. A surface still connected
 * for that wait keeps being sent messages it can only fail, while the replica that could have
 * answered them is passed over; a surface disconnected first hands them straight over. Bean
 * destruction is far too late for that, which is why {@code SlackSocketConnection} closes the
 * connection from a {@code ContextClosedEvent} listener of its own.
 *
 * <p>Asserted through {@code accepting()} rather than by comparing declared {@code @Order} values,
 * so that what is under test is the sequence Spring actually produces.
 */
@org.springframework.context.annotation.Import(AbstractIntegrationTest.SlackStub.class)
@SpringBootTest
@DirtiesContext
class ShutdownOrderTest extends AbstractIntegrationTest {

  @Autowired ConfigurableApplicationContext context;
  @Autowired SpringAgent springAgent;

  @Test
  void theConnectionIsClosedBeforeTheDrainBegins() throws Exception {
    final var acceptingWhenClosed = new AtomicReference<Boolean>();
    doAnswer(
            invocation -> {
              acceptingWhenClosed.set(springAgent.accepting());
              return null;
            })
        .when(slackSocketModeApp)
        .close();

    // The event rather than context.close(): what is under test is the order the two listeners run
    // in, which is the multicaster's decision and is the same either way — and a test that really
    // closed its context would leave the framework holding a context it wants to hand to the next
    // test. It flips the agent's accepting flag for good all the same, hence @DirtiesContext.
    context.publishEvent(new ContextClosedEvent(context));

    assertThat(acceptingWhenClosed)
        .as("the Socket Mode connection was never closed on shutdown")
        .doesNotHaveNullValue();
    assertThat(acceptingWhenClosed.get())
        .as("the drain had already started when the connection was closed")
        .isTrue();
  }
}
