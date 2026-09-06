package me.kezhenxu94.springagent.core.logging;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Turns on the second half of {@link RunMdc}: Reactor carrying the MDC across the thread boundaries
 * a run crosses.
 *
 * <p>{@link Hooks#enableAutomaticContextPropagation()} is global to the JVM and not only to this
 * runtime's own streams — every Reactor chain in the process pays for a capture and a restore
 * around each signal. That is the price of tagging the log lines that matter most, which are the
 * ones this codebase does not write: Spring AI's advisors, the OpenAI client and the tool-calling
 * loop all log from inside the run's chain, on threads nothing here can reach. It is a property so
 * that a deployment which does not want a global hook can have the explicit scopes alone, which
 * still tag everything this runtime logs itself.
 *
 * <p>Idempotent both ways: the hook can be enabled repeatedly, and an accessor registration
 * replaces any earlier one under the same key. Both matter in a test run, where several application
 * contexts start in one JVM.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "app.logging.run-context-propagation",
    havingValue = "true",
    matchIfMissing = true)
public class RunContextPropagationConfiguration {

  @PostConstruct
  void enable() {
    ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcThreadLocalAccessor());
    Hooks.enableAutomaticContextPropagation();
    log.debug("Reactor context propagation is on: a run's MDC follows it across threads");
  }
}
