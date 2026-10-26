package me.kezhenxu94.springagent.integration.telegram.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The parts of the Bot API's types this module reads or writes, and nothing else.
 *
 * <p>Every field Telegram sends that is not declared here is dropped on the way in, which is what
 * keeps a type that grows a field every Bot API release from being something to keep in step with.
 * Snake case on the wire, as Telegram writes it; null fields are left out on the way out, because
 * Telegram reads an explicit {@code null} as a value and refuses some of them.
 *
 * <p>Nested in one class so that {@code TelegramRuntimeHints} can register every one of them for
 * binding by walking a single list: a native image reaches these only through Jackson's reflection,
 * and a type missing from the hints deserializes as nothing at all rather than failing.
 *
 * @see <a href="https://core.telegram.org/bots/api#available-types">Available types</a>
 */
public final class TelegramTypes {

  private TelegramTypes() {}

  /** What every method answers with: a result on success, a code and a description otherwise. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ApiResponse<T>(
      boolean ok, T result, Integer errorCode, String description, ResponseParameters parameters) {}

  /** Why a request failed, where it is something a caller can act on. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ResponseParameters(Integer retryAfter, Long migrateToChatId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Update(long updateId, Message message, CallbackQuery callbackQuery) {}

  /**
   * A message, as delivered or as sent.
   *
   * <p>{@code replyToMessage} carries no {@code replyToMessage} of its own even where it is itself
   * a reply — Telegram stops at one level — which is why a conversation here is not a reply chain.
   * See {@code TelegramConversation}.
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Message(
      int messageId,
      Integer messageThreadId,
      Boolean isTopicMessage,
      User from,
      Chat chat,
      long date,
      Message replyToMessage,
      String text,
      List<MessageEntity> entities,
      String caption,
      List<MessageEntity> captionEntities,
      List<Attachment> photo,
      Attachment document,
      Attachment audio,
      Attachment voice,
      Attachment video,
      Attachment videoNote,
      InlineKeyboardMarkup replyMarkup) {

    /** What was typed, whether as the message or as the caption on what it carries. */
    public String textOrCaption() {
      return text != null ? text : caption;
    }

    /** The entities over {@link #textOrCaption()}, never null. */
    public List<MessageEntity> entitiesOrCaptionEntities() {
      final var found = text != null ? entities : captionEntities;
      return found == null ? List.of() : found;
    }

    /** Whether this message belongs to a forum topic, which is the only thread Telegram has. */
    public boolean inTopic() {
      return Boolean.TRUE.equals(isTopicMessage) && messageThreadId != null;
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record User(long id, boolean isBot, String firstName, String lastName, String username) {

    /** The name a person sees: first and last, or the handle where Telegram has no name. */
    public String displayName() {
      final var name =
          ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
      if (!name.isEmpty()) {
        return name;
      }
      return username == null ? String.valueOf(id) : "@" + username;
    }
  }

  /**
   * The bot itself, as {@code getMe} describes it. {@code canReadAllGroupMessages} is privacy mode
   * the other way round: false is a bot that a group sends only replies and commands.
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Bot(long id, String username, Boolean canReadAllGroupMessages) {}

  /**
   * A chat: {@code private}, {@code group}, {@code supergroup} or {@code channel}. Only the first
   * is a conversation with one person.
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Chat(long id, String type, String title, String username, Boolean isForum) {

    public boolean isPrivate() {
      return "private".equals(type);
    }
  }

  /**
   * A span of a text with a meaning: a mention, a command, or formatting. Offsets and lengths are
   * in UTF-16 code units, which is what a Java string counts in — so a span can be cut out with
   * {@code substring} and needs no translation.
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record MessageEntity(
      String type, int offset, int length, String url, User user, String language) {

    public static MessageEntity of(final String type, final int offset, final int length) {
      return new MessageEntity(type, offset, length, null, null, null);
    }

    public MessageEntity shifted(final int by) {
      return new MessageEntity(type, offset + by, length, url, user, language);
    }

    public MessageEntity resized(final int newOffset, final int newLength) {
      return new MessageEntity(type, newOffset, newLength, url, user, language);
    }

    public int end() {
      return offset + length;
    }
  }

  /**
   * Anything a message carries as a file: a photo size, a document, audio, voice, video. Telegram
   * gives each its own type, but this module needs the same four fields of all of them.
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Attachment(String fileId, String fileName, String mimeType, Long fileSize) {}

  /**
   * A press of an inline keyboard button. {@code data} is the button's {@code callback_data}, and
   * arrives from the client: it names a button this application drew, but nothing stops a client
   * from naming another, so every handler checks what it names rather than trusting it.
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CallbackQuery(String id, User from, Message message, String data) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record InlineKeyboardMarkup(List<List<InlineKeyboardButton>> inlineKeyboard) {

    /** No keyboard at all, which is how one is taken off a message. */
    public static InlineKeyboardMarkup none() {
      return new InlineKeyboardMarkup(List.of());
    }

    public boolean isEmpty() {
      return inlineKeyboard == null || inlineKeyboard.isEmpty();
    }
  }

  /**
   * One button. {@code callbackData} is at most 64 bytes, which is why every id this module puts
   * there is short and why a value that would not fit is left off the keyboard rather than cut.
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record InlineKeyboardButton(String text, String callbackData) {}

  /** A file Telegram holds, as {@code getFile} describes it: where it can be downloaded from. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record TelegramFile(String fileId, Long fileSize, String filePath) {}

  /** Somebody's standing in a chat: {@code creator}, {@code administrator}, {@code member}, … */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ChatMember(String status, User user) {

    /** Whether they are in the chat now, as opposed to having left or been removed. */
    public boolean present() {
      return switch (status == null ? "" : status) {
        case "creator", "administrator", "member", "restricted" -> true;
        default -> false;
      };
    }
  }

  /** What {@code getChat} answers with, narrowed to what a person would want told about a chat. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ChatFullInfo(
      long id,
      String type,
      String title,
      String username,
      String firstName,
      String lastName,
      String description,
      Boolean isForum) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record WebhookInfo(String url, Integer pendingUpdateCount) {}

  /** Every type above, for {@code TelegramRuntimeHints}. */
  public static final List<Class<?>> ALL =
      List.of(
          ApiResponse.class,
          ResponseParameters.class,
          Update.class,
          Message.class,
          User.class,
          Bot.class,
          Chat.class,
          MessageEntity.class,
          Attachment.class,
          CallbackQuery.class,
          InlineKeyboardMarkup.class,
          InlineKeyboardButton.class,
          TelegramFile.class,
          ChatMember.class,
          ChatFullInfo.class,
          WebhookInfo.class);
}
