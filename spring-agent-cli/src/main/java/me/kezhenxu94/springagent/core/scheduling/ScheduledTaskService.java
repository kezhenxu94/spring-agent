package me.kezhenxu94.springagent.core.scheduling;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.messaging.ChangeStreamRequest;
import org.springframework.data.mongodb.core.messaging.MessageListener;
import org.springframework.data.mongodb.core.messaging.MessageListenerContainer;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import reactor.core.publisher.SignalType;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTaskService {

  final ThreadPoolTaskScheduler taskScheduler;

  final SpringAgent springAgent;
  final ScheduledTaskRepo scheduledTaskRepo;
  final MongoTemplate mongoTemplate;
  final MessageListenerContainer mongoListenerContainer;
  final AgentToolsProvider agentToolsProvider;
  final ApplicationEventPublisher eventPublisher;

  final ConcurrentMap<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

  @PostConstruct
  @SuppressWarnings("unchecked")
  public void init() {
    final var now = java.time.Instant.now();
    final var activeTasks = scheduledTaskRepo.findByStatus(ScheduledTask.Status.ACTIVE);
    log.info("Loading {} active scheduled tasks on startup", activeTasks.size());
    activeTasks.forEach(
        task -> {
          if (task.getExpiresAt() != null && task.getExpiresAt().isBefore(now)) {
            log.info("Scheduled task {} has expired, marking as CANCELLED", task.getId());
            mongoTemplate.updateFirst(
                new Query(Criteria.where("id").is(task.getId())),
                new Update().set("status", ScheduledTask.Status.CANCELLED),
                ScheduledTask.class);
          } else {
            schedule(task);
          }
        });

    final var newTaskListener =
        (MessageListener<ChangeStreamDocument<Document>, ? super ScheduledTask>)
            event -> {
              final var task = event.getBody();
              if (task != null && !scheduledFutures.containsKey(task.getId())) {
                log.info("New scheduled task detected via change stream: {}", task.getId());
                schedule(task);
              }
            };
    mongoListenerContainer.register(
        ChangeStreamRequest.builder()
            .collection(ScheduledTask.COLLECTION_NAME)
            .publishTo(newTaskListener)
            .filter(
                Aggregation.newAggregation(
                    Aggregation.match(Criteria.where("status").is(ScheduledTask.Status.ACTIVE))))
            .fullDocumentLookup(FullDocument.UPDATE_LOOKUP)
            .build(),
        ScheduledTask.class);

    final var cancelListener =
        (MessageListener<ChangeStreamDocument<Document>, ? super ScheduledTask>)
            event -> {
              final var task = event.getBody();
              if (task != null) {
                log.info("Scheduled task {} cancelled via change stream", task.getId());
                final var future = scheduledFutures.remove(task.getId());
                if (future != null) {
                  future.cancel(false);
                }
                springAgent.cancel(task.getId());
              }
            };
    mongoListenerContainer.register(
        ChangeStreamRequest.builder()
            .collection(ScheduledTask.COLLECTION_NAME)
            .publishTo(cancelListener)
            .filter(
                Aggregation.newAggregation(
                    Aggregation.match(Criteria.where("status").is(ScheduledTask.Status.CANCELLED))))
            .fullDocumentLookup(FullDocument.UPDATE_LOOKUP)
            .build(),
        ScheduledTask.class);
  }

  public void schedule(final ScheduledTask task) {
    final Runnable runnable = () -> fire(task);
    final ScheduledFuture<?> future;
    if (task.getCronExpression() != null) {
      future = taskScheduler.schedule(runnable, new CronTrigger(task.getCronExpression()));
    } else {
      final var fireAt = task.getScheduledAt();
      if (fireAt.isBefore(java.time.Instant.now())) {
        log.warn(
            "Scheduled task {} has a past scheduledAt {}, firing immediately",
            task.getId(),
            fireAt);
      }
      future = taskScheduler.schedule(runnable, fireAt);
    }
    scheduledFutures.put(task.getId(), future);
    log.info(
        "Scheduled task {}: cron={}, scheduledAt={}",
        task.getId(),
        task.getCronExpression(),
        task.getScheduledAt());
  }

  void fire(final ScheduledTask task) {
    if (!springAgent.isAccepting()) {
      log.info("Shutting down, skipping scheduled task fire: {}", task.getId());
      return;
    }
    if (task.getExpiresAt() != null && task.getExpiresAt().isBefore(java.time.Instant.now())) {
      log.info("Scheduled task {} has expired, cancelling", task.getId());
      mongoTemplate.updateFirst(
          new Query(Criteria.where("id").is(task.getId())),
          new Update().set("status", ScheduledTask.Status.CANCELLED),
          ScheduledTask.class);
      return;
    }
    log.info("Firing scheduled task {}: {}", task.getId(), task.getTaskText());
    try {
      // Synchronous by design: integrations attach their response listeners, todo handlers and
      // tool-context entries to the event, and they are read back immediately below.
      final var firingEvent = new ScheduledTaskFiringEvent(task);
      eventPublisher.publishEvent(firingEvent);

      final var composition =
          agentToolsProvider.compose(
              task.getUserId(),
              task.getChatId(),
              Objects.toString(task.getChatType(), "p2p"),
              AgentScenario.SCHEDULED_TASK,
              firingEvent.todoEventHandler());
      final var promptVariables =
          Map.<String, Object>of(
              "chatId",
              Objects.toString(task.getChatId(), ""),
              "chatType",
              Objects.toString(task.getChatType(), "p2p"),
              "threadId",
              "",
              "parentId",
              "",
              "mentions",
              "none",
              "userId",
              Objects.toString(task.getUserId(), ""));
      final var isCron = task.getCronExpression() != null;

      // Task firings do not accumulate conversation history across runs, so
      // AgentRequest.conversationMemory is false here; conversationId is still passed as it is
      // required as the ToolSearchToolCallingAdvisor's tool-index cache key (autoconfigured, see
      // ToolSearchAdvisorAutoConfiguration).
      final var agentRequest =
          AgentRequest.builder()
              .promptVariables(promptVariables)
              .userMessage(
                  spec -> spec.text("【定时任务触发】请直接执行以下任务，不要创建新的定时任务：\n" + task.getTaskText()))
              .tools(composition.tools())
              .toolCallbacks(composition.toolCallbacks())
              .toolContext(toolContextFor(task, firingEvent))
              .conversationId(task.getRootMessageId())
              .memoriesRootDirectory(composition.memoriesRootDirectory())
              .conversationMemory(AgentScenario.SCHEDULED_TASK.isConversationMemory())
              .requestId(task.getId())
              .build();

      final var listeners = new ArrayList<>(firingEvent.responseListeners());
      listeners.add(new TaskLifecycleListener(task, isCron, composition.agentTools()));
      springAgent.stream(agentRequest, listeners.toArray(new AgentResponseListener[0])).subscribe();

    } catch (Exception e) {
      log.error("Failed to fire scheduled task {}", task.getId(), e);
    }
  }

  /**
   * The task's own identity plus whatever the firing event's listeners contributed. Task values win
   * on conflict, so an integration cannot overwrite the identity core scheduled the run under.
   */
  private static Map<String, Object> toolContextFor(
      final ScheduledTask task, final ScheduledTaskFiringEvent firingEvent) {
    final var toolContext = new LinkedHashMap<String, Object>(firingEvent.toolContext());
    toolContext.put(ToolContexts.KEY_USER_ID, task.getUserId());
    toolContext.put(ToolContexts.KEY_CHAT_ID, Objects.toString(task.getChatId(), ""));
    toolContext.put(ToolContexts.KEY_CHAT_TYPE, Objects.toString(task.getChatType(), "p2p"));
    toolContext.put(ToolContexts.KEY_ROOT_MESSAGE_ID, task.getRootMessageId());
    toolContext.put(ToolContexts.KEY_REPLY_MESSAGE_ID, task.getRootMessageId());
    return toolContext;
  }

  @RequiredArgsConstructor
  private final class TaskLifecycleListener implements AgentResponseListener {
    private final ScheduledTask task;
    private final boolean isCron;
    private final AgentToolsProvider.AgentTools agentTools;

    @Override
    public void onContent(String contentSoFar) {}

    @Override
    public void onUsage(String model, Usage usage) {}

    @Override
    public void onError(Throwable error) {
      log.error("Error in scheduled task {}", task.getId(), error);
    }

    @Override
    public void onFinished(SignalType signal) {
      log.info("Scheduled task {} completed, signal={}", task.getId(), signal);
      if (!isCron) {
        final var terminalStatus =
            switch (signal) {
              case ON_COMPLETE -> ScheduledTask.Status.COMPLETED;
              case ON_ERROR -> ScheduledTask.Status.FAILED;
              default -> null;
            };
        if (terminalStatus != null) {
          mongoTemplate.updateFirst(
              new Query(Criteria.where("id").is(task.getId())),
              new Update().set("status", terminalStatus),
              ScheduledTask.class);
        }
        scheduledFutures.remove(task.getId());
      }
      try {
        agentTools.mcpTools().close();
      } catch (Exception e) {
        log.warn("Failed to close MCP clients for task {}", task.getId(), e);
      }
    }
  }
}
