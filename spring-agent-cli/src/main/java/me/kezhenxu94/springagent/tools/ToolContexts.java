package me.kezhenxu94.springagent.tools;

import com.google.common.base.Strings;
import lombok.experimental.UtilityClass;
import org.springframework.ai.chat.model.ToolContext;

/** Typed keys for values stored in the {@code toolContext} map passed to {@code @Tool} methods. */
@UtilityClass
public class ToolContexts {

  public static final String KEY_USER_ID = "userId";
  public static final String KEY_CHAT_ID = "chatId";
  public static final String KEY_CHAT_TYPE = "chatType";
  public static final String KEY_ROOT_MESSAGE_ID = "rootMessageId";
  public static final String KEY_REPLY_MESSAGE_ID = "replyMessageId";

  public static final ToolContextKey<String> USER_ID = new Key<>(KEY_USER_ID, String.class);
  public static final ToolContextKey<String> CHAT_ID = new Key<>(KEY_CHAT_ID, String.class);
  public static final ToolContextKey<String> CHAT_TYPE = new Key<>(KEY_CHAT_TYPE, String.class);

  /**
   * Identifies the conversation thread the request belongs to, as an opaque string minted by
   * whichever integration received it. Core never interprets its contents.
   */
  public static final ToolContextKey<String> ROOT_MESSAGE_ID =
      new Key<>(KEY_ROOT_MESSAGE_ID, String.class);

  public static final ToolContextKey<String> REPLY_MESSAGE_ID =
      new Key<>(KEY_REPLY_MESSAGE_ID, String.class);

  public record Key<V>(String key, Class<V> clazz) implements ToolContextKey<V> {}

  /**
   * Returns the value for {@code key}, or {@code null} if absent. Throws if a value is present but
   * isn't an instance of {@link ToolContextKey#clazz()}, since that means the wrong type was put
   * under this key.
   */
  public static <T> T get(ToolContext toolContext, ToolContextKey<T> key) {
    if (toolContext == null || toolContext.getContext() == null) {
      return null;
    }
    final var raw = toolContext.getContext().get(key.key());
    if (raw == null) {
      return null;
    }
    if (!key.clazz().isInstance(raw)) {
      throw new IllegalStateException(
          "ToolContext key '"
              + key.key()
              + "' expected "
              + key.clazz().getName()
              + " but was "
              + raw.getClass().getName());
    }
    return key.clazz().cast(raw);
  }

  /** Returns the value for {@code key}, throwing if it is absent or (for a String) blank. */
  public static <T> T require(ToolContext toolContext, ToolContextKey<T> key) {
    final T value = get(toolContext, key);
    if (value == null
        || (value instanceof String stringValue && Strings.isNullOrEmpty(stringValue))) {
      throw new IllegalStateException("Missing required ToolContext key: " + key.key());
    }
    return value;
  }
}
