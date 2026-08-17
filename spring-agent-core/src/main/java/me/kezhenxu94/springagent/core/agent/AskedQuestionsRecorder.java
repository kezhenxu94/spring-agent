package me.kezhenxu94.springagent.core.agent;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.ConditionalOnPersistenceBackend;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.PersistenceProperties.Type;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

/**
 * Leaves a plain assistant message in the conversation saying the questions were put to the user.
 *
 * <p>A workaround for the backends that need one. Asking is a tool call, and {@code
 * JdbcChatMemoryRepository} and the MongoDB repository keep neither the tool response nor the
 * assistant message carrying it. A later run replaying the conversation would see no sign anything
 * had been asked and ask again, which the outstanding-ask guard then refuses — leaving the model
 * with a question it believes it never put and no way forward.
 *
 * <p>Redis keeps tool messages, so no bean exists there.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnPersistenceBackend({Type.JPA, Type.MONGODB})
public class AskedQuestionsRecorder {

  private final ChatMemory chatMemory;

  /** What the agent writes into a conversation itself, as opposed to what the model writes. */
  private final CoreMessages messages;

  /**
   * The same words the model was given when the ask went out, so there is one wording to keep in
   * step. The questions are not in it: a later run has to know that it asked, not what it asked.
   */
  public void record(final String conversationId) {
    try {
      chatMemory.add(conversationId, List.of(new AssistantMessage(messages.get("question-asked"))));
    } catch (Exception e) {
      // A missing note costs a repeated ask later, which is not worth failing the run over.
      log.warn("Failed to record the questions put to conversation {}", conversationId, e);
    }
  }
}
