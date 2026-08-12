package me.kezhenxu94.springagent.core.agent;

import com.google.common.base.Strings;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpringAgent {
  final ChatClient chatClient;
  final ChatMemoryRepository chatMemoryRepository;
  final SpringAgentProperties appConfiguration;

  private final ConcurrentMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
  private final AtomicInteger inFlight = new AtomicInteger(0);
  private volatile boolean accepting = true;

  public boolean isAccepting() {
    return accepting;
  }

  public boolean cancel(final String requestId) {
    final var flag = cancelFlags.get(requestId);
    if (flag == null) return false;
    flag.set(true);
    return true;
  }

  @EventListener(ContextClosedEvent.class)
  public void onShutdown() throws InterruptedException {
    accepting = false;
    log.info("Shutdown: waiting for {} in-flight agent stream(s) to complete", inFlight.get());
    final long deadline = System.currentTimeMillis() + 10 * 60 * 1000L;
    int elapsed = 0;
    while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(2000);
      elapsed += 2;
      if (elapsed % 30 == 0) {
        log.info(
            "Shutdown: still waiting for {} in-flight agent stream(s) ({} seconds elapsed)",
            inFlight.get(),
            elapsed);
      }
    }
    if (inFlight.get() > 0) {
      log.warn("Shutdown timeout reached with {} agent stream(s) still in flight", inFlight.get());
    } else {
      log.info("Shutdown: all in-flight agent streams completed");
    }
  }

  public Flux<ChatResponse> stream(
      final AgentRequest request, final AgentResponseListener... listeners) {
    if (!accepting) {
      return Flux.error(
          new IllegalStateException(
              "Shutting down, rejecting new agent stream: " + request.requestId()));
    }

    final var requestId = request.requestId();
    final var cancelFlag = new AtomicBoolean(false);
    if (requestId != null) {
      cancelFlags.put(requestId, cancelFlag);
    }

    final var contentBuffer = new StringBuilder();
    final var modelNotified = new AtomicBoolean(false);

    return rawStream(request)
        .doOnSubscribe(
            $ -> {
              final var current = inFlight.incrementAndGet();
              log.info("Stream subscribed: requestId={}, inFlight={}", requestId, current);
              notify(listeners, AgentResponseListener::onSubscribe);
            })
        .takeWhile(
            $ ->
                !cancelFlag.get()
                    && Arrays.stream(listeners).allMatch(AgentResponseListener::shouldContinue))
        .doOnNext(
            chatResponse -> {
              final var metadata = chatResponse.getMetadata();
              if (metadata != null) {
                final var model = metadata.getModel();
                if (!Strings.isNullOrEmpty(model) && modelNotified.compareAndSet(false, true)) {
                  notify(listeners, l -> l.onModel(model));
                }
                final var usage = metadata.getUsage();
                if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
                  notify(listeners, l -> l.onUsage(model, usage));
                }
              }
              final var result = chatResponse.getResult();
              if (result == null) return;
              final var output = result.getOutput();
              if (output == null) return;
              final var content = output.getText();
              if (Strings.isNullOrEmpty(content)) return;
              contentBuffer.append(content);
              final var contentSoFar = contentBuffer.toString();
              notify(listeners, l -> l.onContent(contentSoFar));
            })
        .doOnError(error -> notify(listeners, l -> l.onError(error)))
        .doFinally(
            signal -> {
              if (requestId != null) {
                cancelFlags.remove(requestId);
              }
              inFlight.decrementAndGet();
              notify(listeners, l -> l.onFinished(signal));
            });
  }

  private static void notify(
      final AgentResponseListener[] listeners, final Consumer<AgentResponseListener> action) {
    for (final var listener : listeners) {
      try {
        action.accept(listener);
      } catch (Exception e) {
        if (e instanceof InterruptedIOException) {
          Thread.currentThread().interrupt();
        } else {
          log.warn(
              "AgentResponseListener {} threw an exception",
              listener.getClass().getSimpleName(),
              e);
        }
      }
    }
  }

  private Flux<ChatResponse> rawStream(final AgentRequest request) {
    final var renderedSystemPrompt =
        new SystemPromptTemplate(appConfiguration.ai().systemPrompt())
            .render(request.promptVariables());

    final var advisors = new ArrayList<Advisor>();
    advisors.add(
        AutoMemoryToolsAdvisor.builder()
            .memoriesRootDirectory(request.memoriesRootDirectory())
            .build());
    if (request.conversationMemory()) {
      advisors.add(
          MessageChatMemoryAdvisor.builder(
                  MessageWindowChatMemory.builder()
                      .chatMemoryRepository(chatMemoryRepository)
                      .maxMessages(appConfiguration.ai().chatMemory().maxMessages())
                      .build())
              .build());
    }
    advisors.add(SimpleLoggerAdvisor.builder().build());

    return chatClient
        .prompt()
        .system(renderedSystemPrompt)
        .user(request.userMessage())
        .tools(request.tools())
        .tools((Object[]) request.toolCallbacks())
        .toolContext(request.toolContext())
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, request.conversationId()))
        .advisors(advisors.toArray(new Advisor[0]))
        .stream()
        .chatResponse();
  }
}
