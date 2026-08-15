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
 * <p>A workaround, and only for the backends that need one. Asking is a tool call, and {@code
 * JdbcChatMemoryRepository} and the MongoDB repository keep neither the tool response nor the
 * assistant message carrying the call — the JDBC one says so on every write ("does not support tool
 * call messages"). A later run replaying the conversation would see the user's request and no sign
 * that anything had been asked about it, so it asks again; the outstanding-ask guard then refuses,
 * and the model is left with a question it believes it never put and no way to make progress.
 *
 * <p>Redis keeps tool messages, so no bean exists there and the run records nothing — the history
 * already says what this would.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnPersistenceBackend({Type.JDBC, Type.MONGODB})
public class AskedQuestionsRecorder {

  private final ChatMemory chatMemory;

  /** What the agent writes into a conversation itself, as opposed to what the model writes. */
  private final CoreMessages messages;

  /**
   * The same words the model was given when the ask went out, so there is one note to keep in step
   * and one to translate. The questions themselves are not in it: what a later run has to know is
   * that it already asked, not what it asked, and the conversation it is replaying carries that.
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
