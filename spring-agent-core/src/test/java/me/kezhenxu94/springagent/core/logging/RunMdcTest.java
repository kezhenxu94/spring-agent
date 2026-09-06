package me.kezhenxu94.springagent.core.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextRegistry;
import java.util.concurrent.atomic.AtomicReference;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.scheduler.Schedulers;

/**
 * Which run a log line says it belongs to.
 *
 * <p>The last test is the one that matters most, and the one that cannot be reasoned out from the
 * code: a run's identity has to survive being handed to another thread, because everything
 * interesting a run logs is logged after {@code subscribeOn} has moved it off the thread that
 * started it.
 */
class RunMdcTest {

  @AfterEach
  void clear() {
    MDC.clear();
  }

  @Test
  @DisplayName("a scope names the run, and puts back what was there when it closes")
  void shouldRestoreWhatWasThereBefore() {
    try (var outer = RunMdc.of("req-1", "conv-1", "user-1")) {
      assertThat(MDC.get(RunMdc.REQUEST_ID)).isEqualTo("req-1");

      // A subagent, or a queued message answered as the run that queued it ends: started from
      // inside another run's thread, and it must leave that thread as it found it.
      try (var inner = RunMdc.of("req-2", "conv-2", "user-2")) {
        assertThat(MDC.get(RunMdc.REQUEST_ID)).isEqualTo("req-2");
        assertThat(MDC.get(RunMdc.CONVERSATION_ID)).isEqualTo("conv-2");
      }

      assertThat(MDC.get(RunMdc.REQUEST_ID)).isEqualTo("req-1");
      assertThat(MDC.get(RunMdc.CONVERSATION_ID)).isEqualTo("conv-1");
      assertThat(MDC.get(RunMdc.USER_ID)).isEqualTo("user-1");
    }

    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }

  @Test
  @DisplayName("an identity nobody gave is left alone rather than blanked out")
  void shouldNotWriteABlankOverWhatIsKnown() {
    try (var outer = RunMdc.of("req-1", "conv-1", "user-1")) {
      // A background run — a scheduled task firing, a triage run — may name no user at all.
      try (var inner = RunMdc.of("req-2", null, "")) {
        assertThat(MDC.get(RunMdc.REQUEST_ID)).isEqualTo("req-2");
        assertThat(MDC.get(RunMdc.CONVERSATION_ID)).isEqualTo("conv-1");
        assertThat(MDC.get(RunMdc.USER_ID)).isEqualTo("user-1");
      }
    }
  }

  @Test
  @DisplayName("a request names itself")
  void shouldTakeTheIdentityOffARequest() {
    final var request =
        AgentRequest.builder()
            .requestId("req-1")
            .conversationId("conv-1")
            .userId("user-1")
            .scenario(BuiltInScenarios.CHAT)
            .userMessage(spec -> spec.text("hello"))
            .build();

    try (var scope = RunMdc.of(request)) {
      assertThat(MDC.get(RunMdc.REQUEST_ID)).isEqualTo("req-1");
      assertThat(MDC.get(RunMdc.CONVERSATION_ID)).isEqualTo("conv-1");
      assertThat(MDC.get(RunMdc.USER_ID)).isEqualTo("user-1");
    }
  }

  @Test
  @DisplayName("the run's identity follows it onto the thread its stream runs on")
  void shouldFollowTheStreamOntoAnotherThread() {
    // What RunContextPropagationConfiguration does at startup, done here so the assertion is about
    // the accessor rather than about whether an application context happened to start.
    ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcThreadLocalAccessor());
    Hooks.enableAutomaticContextPropagation();
    try {
      final var seenOnTheStreamsThread = new AtomicReference<String>();
      final var streamThread = new AtomicReference<String>();

      try (var scope = RunMdc.of("req-1", "conv-1", "user-1")) {
        Flux.just("chunk")
            .subscribeOn(Schedulers.boundedElastic())
            .doOnNext(
                $ -> {
                  streamThread.set(Thread.currentThread().getName());
                  seenOnTheStreamsThread.set(MDC.get(RunMdc.REQUEST_ID));
                })
            .blockLast();
      }

      assertThat(streamThread.get()).isNotEqualTo(Thread.currentThread().getName());
      assertThat(seenOnTheStreamsThread.get()).isEqualTo("req-1");
    } finally {
      Hooks.disableAutomaticContextPropagation();
    }
  }
}
