package me.kezhenxu94.springagent.integration.feishu.usermodels;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** The card the /config command opens, and the reading back of what was submitted on it. */
class FeishuConfigFormTest {

  private final JsonMapper om = JsonMapper.builder().build();
  private final FeishuConfigForm form =
      new FeishuConfigForm(om, messages(), new ClassPathResource("feishu/config-form.json"));

  private static FeishuMessages messages() {
    return new FeishuMessages(
        new FeishuProperties(
            null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));
  }

  @Test
  @DisplayName("the card parses, and every label placeholder has been filled")
  void rendersValidJson() throws Exception {
    final var card = om.readTree(form.card(List.of(), null, List.of(), "gpt-4o"));

    assertThat(card.path("schema").asString()).isEqualTo("2.0");
    // A placeholder left behind means a key was added to the template and not to
    // renderConfigForm, which reaches the user as the literal word in braces sitting in the card.
    assertThat(form.card(List.of(), null, List.of(), "gpt-4o"))
        .doesNotContainPattern("\\{[a-zA-Z]+\\}");
  }

  @Test
  @DisplayName("the built-in model is always offered, even with nothing registered")
  void defaultAlwaysOffered() throws Exception {
    final var card = om.readTree(form.card(List.of(), null, List.of(), "gpt-4o"));

    final var options = select(card).path("options");
    assertThat(options).hasSize(1);
    assertThat(options.get(0).path("value").asString()).isEqualTo(FeishuConfigForm.DEFAULT_OPTION);
    assertThat(select(card).path("initial_index").asInt()).isEqualTo(1);
  }

  @Test
  @DisplayName("the endpoint in use is the one preselected")
  void preselectsActive() throws Exception {
    final var card =
        om.readTree(
            form.card(List.of(config("kimi"), config("glm")), config("glm"), List.of(), "gpt-4o"));

    // 1-based, and the built-in model occupies the first slot, so the second endpoint is third.
    assertThat(select(card).path("initial_index").asInt()).isEqualTo(3);
    assertThat(select(card).path("options").get(2).path("value").asString()).isEqualTo("glm");
  }

  @Test
  @DisplayName("the token field is masked")
  void tokenIsMasked() throws Exception {
    final var card = om.readTree(form.card(List.of(), null, List.of(), "gpt-4o"));

    final var token =
        elements(card)
            .valueStream()
            .filter(node -> FeishuConfigForm.TOKEN.equals(node.path("name").asString("")))
            .findFirst()
            .orElseThrow();
    assertThat(token.path("input_type").asString()).isEqualTo("password");
  }

  @Test
  @DisplayName("the dropdown carries no label, which Feishu rejects the whole card over")
  void selectHasNoLabel() throws Exception {
    final var card = om.readTree(form.card(List.of(config("kimi")), null, List.of(), "gpt-4o"));

    // select_static has neither label nor label_position — unlike input, which has both. Sending
    // one does not get ignored: the card is refused with `200621 unknown property`, which reaches
    // the user as no card at all. Its caption is a markdown row above it instead.
    assertThat(select(card).has("label")).isFalse();
    assertThat(select(card).has("label_position")).isFalse();
  }

  @Test
  @DisplayName("the models the endpoint lists become options, the configured one as the default")
  void listsBuiltinModels() throws Exception {
    final var card = om.readTree(form.card(List.of(), null, List.of("gpt-4o", "o3"), "gpt-4o"));

    final var options = select(card).path("options");
    assertThat(options).hasSize(2);
    // The configured model is the plain default rather than a second entry beside itself, so that
    // choosing it means having no choice of your own recorded at all.
    assertThat(options.get(0).path("value").asString()).isEqualTo(FeishuConfigForm.DEFAULT_OPTION);
    assertThat(options.get(1).path("value").asString())
        .isEqualTo(FeishuConfigForm.BUILTIN_OPTION + "o3");
  }

  @Test
  @DisplayName("a listing that omits the configured model still offers a way back to it")
  void defaultAlwaysReachable() throws Exception {
    final var card = om.readTree(form.card(List.of(), null, List.of("o3"), "gpt-4o"));

    final var options = select(card).path("options");
    assertThat(options.get(0).path("value").asString()).isEqualTo(FeishuConfigForm.DEFAULT_OPTION);
    assertThat(options).hasSize(2);
  }

  @Test
  @DisplayName("a built-in model in use is the one preselected")
  void preselectsBuiltin() throws Exception {
    final var card =
        om.readTree(form.card(List.of(), builtin("o3"), List.of("gpt-4o", "o3"), "gpt-4o"));

    assertThat(select(card).path("initial_index").asInt()).isEqualTo(2);
  }

  @Test
  @DisplayName("the user's own endpoints come after the built-in ones")
  void userModelsAfterBuiltins() throws Exception {
    final var card =
        om.readTree(
            form.card(List.of(config("kimi")), config("kimi"), List.of("gpt-4o"), "gpt-4o"));

    final var options = select(card).path("options");
    assertThat(options).hasSize(2);
    assertThat(options.get(1).path("value").asString()).isEqualTo("kimi");
    assertThat(select(card).path("initial_index").asInt()).isEqualTo(2);
  }

  @Test
  @DisplayName("a gateway with hundreds of models does not blow the card element limit")
  void capsBuiltinOptions() throws Exception {
    final var many = new java.util.ArrayList<String>();
    for (var i = 0; i < 249; i++) {
      many.add("model-%03d".formatted(i));
    }

    final var card = om.readTree(form.card(List.of(), null, many, "model-200"));

    // Feishu refuses the whole card over this rather than truncating, so the cap is the thing
    // standing between a large gateway and no settings card at all.
    assertThat(select(card).path("options").size())
        .isLessThanOrEqualTo(FeishuConfigForm.MAX_BUILTIN_OPTIONS);
  }

  @Test
  @DisplayName("the configured and in-use models survive the cap")
  void capKeepsWhatMatters() throws Exception {
    final var many = new java.util.ArrayList<String>();
    for (var i = 0; i < 249; i++) {
      many.add("model-%03d".formatted(i));
    }

    final var card = om.readTree(form.card(List.of(), builtin("model-240"), many, "model-200"));

    final var values =
        select(card).path("options").valueStream().map(o -> o.path("value").asString()).toList();
    // Alphabetically both sort far past the cap, so only the pinning keeps them reachable.
    assertThat(values).contains(FeishuConfigForm.DEFAULT_OPTION);
    assertThat(values).contains(FeishuConfigForm.BUILTIN_OPTION + "model-240");
  }

  @Test
  @DisplayName("the model field alone names a built-in model rather than adding an endpoint")
  void builtinByName() {
    final var submission = form.read(Map.of(FeishuConfigForm.MODEL, "some-model-not-listed"));

    assertThat(submission.builtinByName()).isTrue();
    assertThat(submission.complete()).isFalse();
  }

  @Test
  @DisplayName("a full endpoint is not mistaken for naming a built-in model")
  void fullEndpointIsNotBuiltin() {
    final var submission =
        form.read(
            Map.of(
                FeishuConfigForm.NAME, "kimi",
                FeishuConfigForm.BASE_URL, "https://kimi/v1",
                FeishuConfigForm.MODEL, "kimi-k2",
                FeishuConfigForm.TOKEN, "sk-x"));

    assertThat(submission.builtinByName()).isFalse();
    assertThat(submission.complete()).isTrue();
  }

  @Test
  @DisplayName("a submission of the dropdown alone is not read as adding a model")
  void switchOnly() {
    final var submission = form.read(Map.of(FeishuConfigForm.ACTIVE, "kimi"));

    assertThat(submission.active()).isEqualTo("kimi");
    assertThat(submission.adding()).isFalse();
  }

  @Test
  @DisplayName("a half-filled add is spotted rather than saved")
  void incompleteAdd() {
    final var submission =
        form.read(Map.of(FeishuConfigForm.NAME, "kimi", FeishuConfigForm.MODEL, "kimi-k2"));

    assertThat(submission.adding()).isTrue();
    assertThat(submission.complete()).isFalse();
  }

  @Test
  @DisplayName("blank fields count as not filled in")
  void blanksIgnored() {
    final var submission =
        form.read(
            Map.of(
                FeishuConfigForm.NAME,
                "  ",
                FeishuConfigForm.ACTIVE,
                FeishuConfigForm.DEFAULT_OPTION));

    assertThat(submission.name()).isNull();
    assertThat(submission.adding()).isFalse();
    assertThat(submission.active()).isEqualTo(FeishuConfigForm.DEFAULT_OPTION);
  }

  @Test
  @DisplayName("how hard to think is a list, and the whole list")
  void effortIsAList() throws Exception {
    final var card = om.readTree(form.card(List.of(), null, List.of(), "gpt-4o"));

    final var values =
        effortSelect(card)
            .path("options")
            .valueStream()
            .map(option -> option.path("value").asString())
            .toList();
    // Every effort the SDK knows, plus the two ways of choosing none of them. A user must never
    // have to type one: Spring AI takes it as a bare string and an endpoint fails on a typo.
    assertThat(values).containsAll(ReasoningEfforts.VALUES);
    assertThat(values).contains(FeishuConfigForm.EFFORT_INHERIT_OPTION, ReasoningEfforts.NOT_SENT);
  }

  @Test
  @DisplayName("the effort in force is the one preselected")
  void preselectsEffort() throws Exception {
    final var active = config("kimi").toBuilder().reasoningEffort("high").build();

    final var card = om.readTree(form.card(List.of(active), active, List.of(), "gpt-4o"));

    final var index = effortSelect(card).path("initial_index").asInt();
    // 1-based, and the application's own setting is the first option, so the values follow it.
    assertThat(effortSelect(card).path("options").get(index - 1).path("value").asString())
        .isEqualTo("high");
  }

  @Test
  @DisplayName("a model with no effort of its own shows the application's setting")
  void preselectsInherit() throws Exception {
    final var card = om.readTree(form.card(List.of(), null, List.of(), "gpt-4o"));

    assertThat(effortSelect(card).path("initial_index").asInt()).isEqualTo(1);
    assertThat(effortSelect(card).path("options").get(0).path("value").asString())
        .isEqualTo(FeishuConfigForm.EFFORT_INHERIT_OPTION);
  }

  @Test
  @DisplayName("the row that means the application's model preselects the built-in option")
  void preselectsDefaultRow() throws Exception {
    // The row names no model, so no built-in option matches it by name — and the configured model
    // need not be the first option, a gateway that lists it having it among the others.
    final var defaultRow =
        UserModelConfig.builder().name("@").reasoningEffort("max").activated(true).build();

    final var card =
        om.readTree(form.card(List.of(), defaultRow, List.of("a-model", "gpt-4o"), "gpt-4o"));

    final var index = select(card).path("initial_index").asInt();
    assertThat(select(card).path("options").get(index - 1).path("value").asString())
        .isEqualTo(FeishuConfigForm.DEFAULT_OPTION);
  }

  @Test
  @DisplayName("the effort caption is the size of the field labels it sits between")
  void effortCaptionMatchesTheLabels() throws Exception {
    final var card = om.readTree(form.card(List.of(), null, List.of(), "gpt-4o"));

    // It stands in for an input's label, select_static having none of its own, so it has to read as
    // one rather than as the smaller section captions.
    final var captions =
        elements(card)
            .valueStream()
            .filter(node -> "markdown".equals(node.path("tag").asString("")))
            .map(node -> node.path("text_size").asString(""))
            .toList();
    assertThat(captions).contains("normal_v2");
  }

  @Test
  @DisplayName("a press leaves a summary rather than a form, and never the token")
  void summaryReplacesTheForm() throws Exception {
    final var active =
        config("kimi").toBuilder().reasoningEffort("high").apiKeyCipher("sealed-secret").build();

    final var summary = form.summary("Saved kimi.", active, "gpt-4o");
    final var card = om.readTree(summary);

    assertThat(card.path("body").path("elements").valueStream())
        .noneMatch(node -> "form".equals(node.path("tag").asString("")));
    assertThat(summary)
        .contains("Saved kimi.")
        .contains("kimi")
        .contains("kimi-model")
        .contains("https://x/v1")
        .contains("high")
        .doesNotContain("sealed-secret");
  }

  @Test
  @DisplayName("the summary of the application's model names it without a URL of anybody's")
  void summaryOfTheDefault() throws Exception {
    final var summary = form.summary("Now on the built-in model.", null, "gpt-4o");

    assertThat(summary).contains("gpt-4o").doesNotContain("null");
  }

  private static UserModelConfig builtin(final String model) {
    return UserModelConfig.builder().name("@" + model).model(model).build();
  }

  private static UserModelConfig config(final String name) {
    return UserModelConfig.builder()
        .name(name)
        .model(name + "-model")
        .baseUrl("https://x/v1")
        .build();
  }

  private static tools.jackson.databind.JsonNode elements(
      final tools.jackson.databind.JsonNode card) {
    return card.path("body")
        .path("elements")
        .valueStream()
        .filter(node -> "form".equals(node.path("tag").asString("")))
        .findFirst()
        .orElseThrow()
        .path("elements");
  }

  private static tools.jackson.databind.JsonNode select(
      final tools.jackson.databind.JsonNode card) {
    return selects(card).getFirst();
  }

  /** The effort dropdown is the second, the model one being added first. */
  private static tools.jackson.databind.JsonNode effortSelect(
      final tools.jackson.databind.JsonNode card) {
    return selects(card).get(1);
  }

  private static List<tools.jackson.databind.JsonNode> selects(
      final tools.jackson.databind.JsonNode card) {
    return elements(card)
        .valueStream()
        .filter(node -> "select_static".equals(node.path("tag").asString("")))
        .toList();
  }
}
