package me.kezhenxu94.springagent.integration.feishu.greeting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.stream.StreamSupport;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import me.kezhenxu94.springagent.integration.feishu.greeting.FeishuUpdates.Note;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What somebody meets when they open the chat for the first time, and when they come back. */
class FeishuGreetingCardsTest {

  private final JsonMapper objectMapper = new JsonMapper();

  private FeishuUpdates updates;
  private FeishuGreetingCards cards;

  @BeforeEach
  void setUp() {
    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));
    updates =
        new FeishuUpdates(
            new DefaultResourceLoader(),
            messages,
            "classpath:/feishu/welcome.md",
            "classpath:/feishu/updates/");
    cards =
        new FeishuGreetingCards(
            objectMapper,
            messages,
            updates,
            new ClassPathResource("feishu/welcome-card.json"),
            new ClassPathResource("feishu/update-card.json"));
  }

  private List<JsonNode> elementsOf(final String json) {
    final var elements = objectMapper.readTree(json).path("body").path("elements");
    return StreamSupport.stream(elements.spliterator(), false).toList();
  }

  @Test
  @DisplayName("the welcome card carries the note's own title and prose")
  void carriesTheNote() throws Exception {
    final var json = cards.welcome();

    final var card = objectMapper.readTree(json);
    assertThat(card.path("header").path("title").path("content").asString())
        .isEqualTo(updates.welcome().title());
    final var intro =
        elementsOf(json).stream()
            .filter(e -> "intro".equals(e.path("element_id").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(intro.path("content").asString()).isEqualTo(updates.welcome().body());
  }

  @Test
  @DisplayName("one pressable row per suggestion, carrying the words it shows")
  void offersEachSuggestion() throws Exception {
    final var chips =
        elementsOf(cards.welcome()).stream()
            .filter(e -> "interactive_container".equals(e.path("tag").asString()))
            .toList();

    assertThat(chips).hasSameSizeAs(updates.welcome().suggestions());
    for (var i = 0; i < chips.size(); i++) {
      final var chip = chips.get(i);
      final var prompt = updates.welcome().suggestions().get(i);
      final var behavior = chip.path("behaviors").get(0);
      assertThat(behavior.path("type").asString()).isEqualTo("callback");
      assertThat(behavior.path("value").path("button").asString())
          .isEqualTo(FeishuSuggestions.ACTION);
      // The words on the row and the words the tap sends are the same words, so nobody presses one
      // thing and asks another.
      assertThat(behavior.path("value").path("prompt").asString()).isEqualTo(prompt);
      assertThat(chip.path("elements").get(0).path("content").asString()).isEqualTo(prompt);
      assertThat(chip.path("has_border").asBoolean()).isTrue();
    }
  }

  @Test
  @DisplayName("the update card names how many notes it is about to list")
  void countsTheNotes() throws Exception {
    final var one = List.of(new Note(4, "four", "the fourth"));
    final var two = List.of(new Note(4, "four", "the fourth"), new Note(5, "five", "the fifth"));

    assertThat(titleOf(cards.update(one))).contains("1 update").doesNotContain("1 updates");
    assertThat(titleOf(cards.update(two))).contains("2 updates");
  }

  @Test
  @DisplayName("each note is a numbered row carrying its title and its prose")
  void listsEachNote() throws Exception {
    final var json = cards.update(List.of(new Note(7, "I learned a thing", "and here it is")));

    final var rows = elementsOf(json);
    assertThat(rows).hasSize(1);
    final var columns = rows.getFirst().path("columns");
    assertThat(columns.get(0).path("elements").get(0).path("content").asString()).contains("v7");
    final var body = columns.get(1).path("elements");
    assertThat(body.get(0).path("content").asString()).isEqualTo("**I learned a thing**");
    assertThat(body.get(1).path("content").asString()).isEqualTo("and here it is");
  }

  @Test
  @DisplayName("nothing on the update card is bordered, because nothing on it can be pressed")
  void bordersOnlyWhatIsPressable() throws Exception {
    final var json = cards.update(updates.since(0));

    assertThat(json).doesNotContain("interactive_container").doesNotContain("has_border");
  }

  private String titleOf(final String json) {
    return objectMapper.readTree(json).path("header").path("title").path("content").asString();
  }
}
