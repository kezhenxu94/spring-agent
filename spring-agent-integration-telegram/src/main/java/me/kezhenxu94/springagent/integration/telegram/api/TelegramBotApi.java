package me.kezhenxu94.springagent.integration.telegram.api;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.ApiResponse;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Bot;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.ChatFullInfo;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.ChatMember;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardMarkup;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Message;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.TelegramFile;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Update;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.WebhookInfo;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Bot API methods this module calls, and nothing else.
 *
 * <p>Every method is a POST of a JSON object to {@code /bot<token>/<method>} answered by {@code
 * {"ok": …, "result": …}}, so a client is a dozen lines of RestClient and a record per type — see
 * {@link TelegramTypes} for why no SDK. What it adds over a bare call is the two things every
 * caller would otherwise get wrong: an error is read out of the body rather than out of the status
 * line, where Telegram puts the part worth knowing (a 429's {@code retry_after}, an edit that
 * changed nothing), and a failure never says the URL — see {@link TelegramApiException}.
 *
 * <p>Two clients over one builder. Long polling holds a request open for as long as Telegram has
 * nothing to say, so its read timeout has to be longer than the poll; every other call is bounded
 * by {@code app.telegram.request-timeout}, for the reason {@code app.slack.request-timeout} is: a
 * write that never returns is a stuck run.
 *
 * @see <a href="https://core.telegram.org/bots/api">Telegram Bot API</a>
 */
public class TelegramBotApi {

  /**
   * How many formatted spans Telegram takes on one message. Past it, the extra spans are dropped
   * rather than the message refused — the text is what matters, its bold less so.
   */
  static final int MAX_ENTITIES = 100;

  private final RestClient client;
  private final RestClient polling;
  private final JsonMapper json;
  private final String base;
  private final String token;

  public TelegramBotApi(
      final RestClient.Builder builder,
      final JsonMapper json,
      final String apiUrl,
      final String token,
      final Duration requestTimeout,
      final Duration pollTimeout) {
    this.client = builder.clone().requestFactory(factory(requestTimeout)).build();
    // The poll's own length plus a margin, so that Telegram answering an empty poll on time is
    // never
    // mistaken for Telegram not answering.
    this.polling =
        builder.clone().requestFactory(factory(pollTimeout.plus(requestTimeout))).build();
    this.json = json;
    this.base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
    this.token = token;
  }

  private static JdkClientHttpRequestFactory factory(final Duration timeout) {
    final var factory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
    factory.setReadTimeout(timeout);
    return factory;
  }

  public Bot getMe() {
    return call("getMe", Map.of(), new ParameterizedTypeReference<>() {});
  }

  public WebhookInfo getWebhookInfo() {
    return call("getWebhookInfo", Map.of(), new ParameterizedTypeReference<>() {});
  }

  /**
   * What has happened since {@code offset}, waiting up to {@code timeout} for something to.
   *
   * <p>Asking with an offset is also what confirms everything before it: Telegram forgets an update
   * once a later poll names an offset past it, and redelivers it to every poll until then.
   */
  public List<Update> getUpdates(
      final long offset, final Duration timeout, final List<String> allowedUpdates) {
    final var body = new LinkedHashMap<String, Object>();
    body.put("offset", offset);
    body.put("timeout", timeout.toSeconds());
    body.put("allowed_updates", allowedUpdates);
    return call(polling, "getUpdates", body, new ParameterizedTypeReference<>() {});
  }

  /**
   * Sends {@code text} to {@code chatId}.
   *
   * @param threadId the forum topic to send into, or null outside one
   * @param replyTo the message this answers, or null; a reply whose original has gone is sent
   *     anyway rather than refused
   * @param keyboard buttons under the message, or null
   */
  public Message sendMessage(
      final String chatId,
      final Integer threadId,
      final TelegramText text,
      final Integer replyTo,
      final InlineKeyboardMarkup keyboard) {
    final var body = new LinkedHashMap<String, Object>();
    body.put("chat_id", chatId);
    if (threadId != null) {
      body.put("message_thread_id", threadId);
    }
    text(body, text);
    if (replyTo != null) {
      body.put(
          "reply_parameters", Map.of("message_id", replyTo, "allow_sending_without_reply", true));
    }
    if (keyboard != null) {
      body.put("reply_markup", keyboard);
    }
    return call("sendMessage", body, new ParameterizedTypeReference<>() {});
  }

  /** Replaces a message's text, and its keyboard with {@code keyboard} — none where null. */
  public void editMessageText(
      final String chatId,
      final int messageId,
      final TelegramText text,
      final InlineKeyboardMarkup keyboard) {
    final var body = new LinkedHashMap<String, Object>();
    body.put("chat_id", chatId);
    body.put("message_id", messageId);
    text(body, text);
    body.put("reply_markup", keyboard == null ? InlineKeyboardMarkup.none() : keyboard);
    call("editMessageText", body, new ParameterizedTypeReference<ApiResponse<Object>>() {});
  }

  /** Replaces a message's keyboard alone, leaving its text as it is. */
  public void editMessageReplyMarkup(
      final String chatId, final int messageId, final InlineKeyboardMarkup keyboard) {
    final var body = new LinkedHashMap<String, Object>();
    body.put("chat_id", chatId);
    body.put("message_id", messageId);
    body.put("reply_markup", keyboard == null ? InlineKeyboardMarkup.none() : keyboard);
    call("editMessageReplyMarkup", body, new ParameterizedTypeReference<ApiResponse<Object>>() {});
  }

  public void deleteMessage(final String chatId, final int messageId) {
    call(
        "deleteMessage",
        Map.of("chat_id", chatId, "message_id", messageId),
        new ParameterizedTypeReference<ApiResponse<Boolean>>() {});
  }

  /**
   * Tells the client a button press was received, with {@code text} as a notice only the presser
   * sees — or nothing, where null. Every press has to be answered, or the client shows the button
   * as loading until it gives up.
   */
  public void answerCallbackQuery(final String callbackQueryId, final String text) {
    final var body = new LinkedHashMap<String, Object>();
    body.put("callback_query_id", callbackQueryId);
    if (text != null) {
      body.put("text", text);
    }
    call("answerCallbackQuery", body, new ParameterizedTypeReference<ApiResponse<Boolean>>() {});
  }

  /** Puts {@code emoji} on a message, replacing whatever reaction the bot had left there. */
  public void setMessageReaction(final String chatId, final int messageId, final String emoji) {
    call(
        "setMessageReaction",
        Map.of(
            "chat_id",
            chatId,
            "message_id",
            messageId,
            "reaction",
            List.of(Map.of("type", "emoji", "emoji", emoji))),
        new ParameterizedTypeReference<ApiResponse<Boolean>>() {});
  }

  public TelegramFile getFile(final String fileId) {
    return call("getFile", Map.of("file_id", fileId), new ParameterizedTypeReference<>() {});
  }

  /** The bytes of a file {@link #getFile} described. */
  public byte[] download(final TelegramFile file) {
    try {
      return client
          .get()
          .uri(URI.create(base + "/file/bot" + token + "/" + file.filePath()))
          .retrieve()
          .body(byte[].class);
    } catch (RestClientException e) {
      throw new TelegramApiException("download", e);
    }
  }

  /** Uploads {@code file} into {@code chatId} as a document, with {@code caption} under it. */
  public Message sendDocument(
      final String chatId,
      final Integer threadId,
      final File file,
      final String caption,
      final Integer replyTo) {
    final var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("chat_id", chatId);
    if (threadId != null) {
      parts.add("message_thread_id", String.valueOf(threadId));
    }
    parts.add("document", new FileSystemResource(file));
    if (caption != null && !caption.isBlank()) {
      parts.add("caption", caption);
    }
    if (replyTo != null) {
      // A multipart field is a string, so a structured parameter goes in as its JSON.
      parts.add(
          "reply_parameters",
          json.writeValueAsString(
              Map.of("message_id", replyTo, "allow_sending_without_reply", true)));
    }
    final ApiResponse<Message> response;
    try {
      response =
          client
              .post()
              .uri(uri("sendDocument"))
              .contentType(MediaType.MULTIPART_FORM_DATA)
              .body(parts)
              .retrieve()
              .onStatus(status -> true, (request, answer) -> {})
              .body(new ParameterizedTypeReference<>() {});
    } catch (RestClientException e) {
      throw new TelegramApiException("sendDocument", e);
    }
    return result("sendDocument", response);
  }

  public ChatFullInfo getChat(final String chatId) {
    return call("getChat", Map.of("chat_id", chatId), new ParameterizedTypeReference<>() {});
  }

  public ChatMember getChatMember(final String chatId, final long userId) {
    return call(
        "getChatMember",
        Map.of("chat_id", chatId, "user_id", userId),
        new ParameterizedTypeReference<>() {});
  }

  public List<ChatMember> getChatAdministrators(final String chatId) {
    return call(
        "getChatAdministrators", Map.of("chat_id", chatId), new ParameterizedTypeReference<>() {});
  }

  public int getChatMemberCount(final String chatId) {
    final Integer count =
        call(
            "getChatMemberCount", Map.of("chat_id", chatId), new ParameterizedTypeReference<>() {});
    return count == null ? 0 : count;
  }

  private static void text(final Map<String, Object> body, final TelegramText text) {
    body.put("text", text.text());
    if (!text.entities().isEmpty()) {
      final var entities = text.entities();
      body.put(
          "entities",
          entities.size() > MAX_ENTITIES ? entities.subList(0, MAX_ENTITIES) : entities);
    }
    // Every message this module sends is either an answer being written or a sentence about one,
    // and a preview card for the first link in it is a second message's worth of space that moves
    // every time the answer is rewritten.
    body.put("link_preview_options", Map.of("is_disabled", true));
  }

  private <T> T call(
      final String method,
      final Object body,
      final ParameterizedTypeReference<ApiResponse<T>> type) {
    return call(client, method, body, type);
  }

  private <T> T call(
      final RestClient restClient,
      final String method,
      final Object body,
      final ParameterizedTypeReference<ApiResponse<T>> type) {
    final ApiResponse<T> response;
    try {
      response =
          restClient
              .post()
              .uri(uri(method))
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              // Every status is read as a body: Telegram explains a failure in the JSON it sends
              // with it, and the default handler would throw that away for an exception carrying
              // the URL — and the URL carries the token.
              .onStatus(status -> true, (request, answer) -> {})
              .body(type);
    } catch (RestClientException e) {
      throw new TelegramApiException(method, e);
    }
    return result(method, response);
  }

  private static <T> T result(final String method, final ApiResponse<T> response) {
    if (response == null) {
      throw new TelegramApiException(method, 0, "Telegram answered with nothing", null);
    }
    if (!response.ok()) {
      final var retryAfter =
          response.parameters() == null || response.parameters().retryAfter() == null
              ? null
              : Duration.ofSeconds(response.parameters().retryAfter());
      throw new TelegramApiException(
          method,
          response.errorCode() == null ? 0 : response.errorCode(),
          response.description(),
          retryAfter);
    }
    return response.result();
  }

  private URI uri(final String method) {
    return URI.create(base + "/bot" + token + "/" + method);
  }
}
