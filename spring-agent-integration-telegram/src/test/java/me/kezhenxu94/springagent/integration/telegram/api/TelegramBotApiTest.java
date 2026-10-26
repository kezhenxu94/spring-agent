package me.kezhenxu94.springagent.integration.telegram.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * That a Telegram answer is read the way Telegram writes it, and that no failure says the token.
 *
 * <p>Against a real HTTP server rather than a mocked client, because what is under test is the
 * reading: snake case into records, and an error that arrives as a 4xx with its explanation in the
 * body — which a client's default error handling throws away.
 */
class TelegramBotApiTest {

  private static final String TOKEN = "123456:SECRET-token";

  private MockWebServer server;
  private TelegramBotApi telegram;

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.start();
    telegram = api(server.url("/").toString());
  }

  @AfterEach
  void stop() throws IOException {
    server.shutdown();
  }

  private static TelegramBotApi api(final String url) {
    return new TelegramBotApi(
        RestClient.builder(),
        JsonMapper.builder().build(),
        url,
        TOKEN,
        Duration.ofSeconds(2),
        Duration.ofSeconds(1));
  }

  private void answer(final int status, final String body) {
    server.enqueue(
        new MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody(body));
  }

  @Test
  @DisplayName("a result is read out of snake case")
  void shouldReadSnakeCase() throws InterruptedException {
    answer(
        200,
        "{\"ok\":true,\"result\":{\"id\":42,\"is_bot\":true,\"first_name\":\"Bot\",\"username\":\"the_bot\",\"can_join_groups\":true,\"can_read_all_group_messages\":false}}");

    final var me = telegram.getMe();

    assertThat(me.id()).isEqualTo(42);
    assertThat(me.canReadAllGroupMessages()).isFalse();
    assertThat(me.username()).isEqualTo("the_bot");
    assertThat(server.takeRequest().getPath()).isEqualTo("/bot" + TOKEN + "/getMe");
  }

  @Test
  @DisplayName("a 429 is read for how long to wait, and its message does not carry the token")
  void shouldReadARateLimit() {
    answer(
        429,
        "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after"
            + " 5\",\"parameters\":{\"retry_after\":5}}");

    assertThatThrownBy(() -> telegram.getMe())
        .isInstanceOfSatisfying(
            TelegramApiException.class,
            e -> {
              assertThat(e.rateLimited()).isTrue();
              assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(5));
              assertThat(e.getMessage()).doesNotContain(TOKEN);
            });
  }

  @Test
  @DisplayName("an edit that changes nothing is recognisable as such")
  void shouldRecogniseANoOpEdit() {
    answer(
        400,
        "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message is not modified:"
            + " specified new message content and reply markup are exactly the same\"}");

    assertThatThrownBy(() -> telegram.editMessageText("1", 2, TelegramText.plain("x"), null))
        .isInstanceOfSatisfying(
            TelegramApiException.class, e -> assertThat(e.notModified()).isTrue());
  }

  @Test
  @DisplayName("Telegram unreachable is a failure that names the method and not the URL")
  void shouldNotLeakTheTokenWhenUnreachable() throws IOException {
    final var url = server.url("/").toString();
    server.shutdown();

    assertThatThrownBy(() -> api(url).getMe())
        .isInstanceOf(TelegramApiException.class)
        .hasMessageContaining("getMe")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN))
        .satisfies(e -> assertThat(String.valueOf(e.getCause())).doesNotContain("SECRET"));
  }

  @Test
  @DisplayName("a message goes out with its spans in snake case and no null fields")
  void shouldWriteEntities() throws InterruptedException {
    answer(
        200,
        "{\"ok\":true,\"result\":{\"message_id\":7,\"chat\":{\"id\":1,\"type\":\"private\"},\"date\":0}}");

    final var sent =
        telegram.sendMessage(
            "1", null, TelegramText.builder().styled("bold", "hi").build(), 3, null);

    assertThat(sent.messageId()).isEqualTo(7);
    final var body = server.takeRequest().getBody().readUtf8();
    assertThat(body)
        .contains("\"entities\":[{\"type\":\"bold\",\"offset\":0,\"length\":2}]")
        .contains("\"reply_parameters\"")
        .contains("\"link_preview_options\"")
        .doesNotContain("null");
  }

  @Test
  @DisplayName("updates are read with their messages and presses")
  void shouldReadUpdates() {
    answer(
        200,
        "{\"ok\":true,\"result\":[{\"update_id\":10,\"message\":{\"message_id\":1,\"from\":{\"id\":5,\"is_bot\":false,\"first_name\":\"A\"},\"chat\":{\"id\":5,\"type\":\"private\"},\"date\":1,\"text\":\"hi\",\"entities\":[{\"type\":\"bot_command\",\"offset\":0,\"length\":2}]}},{\"update_id\":11,\"callback_query\":{\"id\":\"q\",\"from\":{\"id\":5,\"is_bot\":false,\"first_name\":\"A\"},\"data\":\"sa_stop:5:1\"}}]}");

    final var updates = telegram.getUpdates(0, Duration.ZERO, List.of("message"));

    assertThat(updates).hasSize(2);
    assertThat(updates.getFirst().message().chat().isPrivate()).isTrue();
    assertThat(updates.getFirst().message().entities().getFirst().type()).isEqualTo("bot_command");
    assertThat(updates.get(1).callbackQuery().data()).isEqualTo("sa_stop:5:1");
  }
}
