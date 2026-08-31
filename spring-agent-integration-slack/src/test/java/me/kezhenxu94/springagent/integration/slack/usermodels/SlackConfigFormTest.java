package me.kezhenxu94.springagent.integration.slack.usermodels;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.InputBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.composition.OptionObject;
import com.slack.api.model.block.element.StaticSelectElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import me.kezhenxu94.springagent.integration.slack.config.SlackProperties;
import me.kezhenxu94.springagent.integration.slack.handler.SlackQuestionForm.ViewValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The form /config posts, and the reading back of what was submitted on it. */
class SlackConfigFormTest {

  private final SlackConfigForm form = new SlackConfigForm(new SlackMessages(properties()));

  private static SlackProperties properties() {
    return new SlackProperties("xoxb", "xapp", "U0BOT", "T0TEAM", Locale.ENGLISH, null, null);
  }

  @Test
  @DisplayName("the built-in model is always offered, even with nothing registered")
  void defaultAlwaysOffered() {
    final var options = options(form.blocks(List.of(), null, List.of(), "gpt-4o"));

    assertThat(options).hasSize(1);
    assertThat(options.get(0).getValue()).isEqualTo(SlackConfigForm.DEFAULT_OPTION);
  }

  @Test
  @DisplayName("the models the endpoint lists become options, the configured one as the default")
  void listsBuiltinModels() {
    final var options = options(form.blocks(List.of(), null, List.of("gpt-4o", "o3"), "gpt-4o"));

    assertThat(options).hasSize(2);
    assertThat(options.get(0).getValue()).isEqualTo(SlackConfigForm.DEFAULT_OPTION);
    assertThat(options.get(1).getValue()).isEqualTo(SlackConfigForm.BUILTIN_OPTION + "o3");
  }

  @Test
  @DisplayName("a listing that omits the configured model still offers a way back to it")
  void defaultAlwaysReachable() {
    final var options = options(form.blocks(List.of(), null, List.of("o3"), "gpt-4o"));

    assertThat(options.get(0).getValue()).isEqualTo(SlackConfigForm.DEFAULT_OPTION);
  }

  @Test
  @DisplayName("a gateway with hundreds of models does not blow the option limit")
  void capsBuiltinOptions() {
    final var many = new ArrayList<String>();
    for (var i = 0; i < 249; i++) {
      many.add("model-%03d".formatted(i));
    }

    final var options = options(form.blocks(List.of(), builtin("model-240"), many, "model-200"));

    // Slack refuses a static select with more than 100 options, so the cap is what stands between
    // a large gateway and no settings form at all.
    assertThat(options.size()).isLessThanOrEqualTo(SlackConfigForm.MAX_BUILTIN_OPTIONS + 1);
    final var values = options.stream().map(OptionObject::getValue).toList();
    // Both sort far past the cap alphabetically, so only the pinning keeps them reachable.
    assertThat(values).contains(SlackConfigForm.DEFAULT_OPTION);
    assertThat(values).contains(SlackConfigForm.BUILTIN_OPTION + "model-240");
  }

  @Test
  @DisplayName("the user's own endpoints come after the built-in ones")
  void userModelsLast() {
    final var options =
        options(form.blocks(List.of(config("kimi")), config("kimi"), List.of("gpt-4o"), "gpt-4o"));

    assertThat(options).hasSize(2);
    assertThat(options.get(1).getValue()).isEqualTo("kimi");
  }

  @Test
  @DisplayName("the modal declares itself submittable and routes back by callback id")
  void modalIsWellFormed() {
    final var view = form.view(List.of(), null, List.of(), "gpt-4o");

    assertThat(view.getType()).isEqualTo("modal");
    assertThat(view.getCallbackId()).isEqualTo(SlackConfigForm.CALLBACK_ID);
    assertThat(view.getSubmit()).isNotNull();
    // Slack refuses a view whose title runs past 24 characters.
    assertThat(view.getTitle().getText().length()).isLessThanOrEqualTo(24);
  }

  @Test
  @DisplayName("the in-message fallback carries its own submit button, the modal does not")
  void fallbackHasSubmitButton() {
    final var modal = form.blocks(List.of(), null, List.of(), "gpt-4o");
    final var message = form.messageBlocks(List.of(), null, List.of(), "gpt-4o");

    // A modal submits itself; a message needs something to press, and the two must not be mixed up
    // or one of the two ways in silently stops working.
    assertThat(modal).noneMatch(ActionsBlock.class::isInstance);
    assertThat(message).anyMatch(ActionsBlock.class::isInstance);
  }

  @Test
  @DisplayName("the in-message fallback warns that the token will be visible, the modal does not")
  void fallbackWarnsAboutTheToken() {
    assertThat(tokenHint(form.messageBlocks(List.of(), null, List.of(), "gpt-4o"))).isNotNull();
    assertThat(tokenHint(form.blocks(List.of(), null, List.of(), "gpt-4o"))).isNull();
  }

  @Test
  @DisplayName("both ways in offer the same choices")
  void bothFormsOfferTheSameOptions() {
    final var modal = options(form.blocks(List.of(config("kimi")), null, List.of("o3"), "gpt-4o"));
    final var message =
        options(form.messageBlocks(List.of(config("kimi")), null, List.of("o3"), "gpt-4o"));

    assertThat(message.stream().map(OptionObject::getValue).toList())
        .isEqualTo(modal.stream().map(OptionObject::getValue).toList());
  }

  @Test
  @DisplayName("every input is optional, so the form can be submitted to change only one thing")
  void inputsAreOptional() {
    final var blocks = form.blocks(List.of(), null, List.of(), "gpt-4o");

    assertThat(blocks)
        .filteredOn(InputBlock.class::isInstance)
        .allSatisfy(block -> assertThat(((InputBlock) block).isOptional()).isTrue());
  }

  @Test
  @DisplayName("what the user typed is read back under the block it was typed into")
  void readsSubmission() {
    final var submission =
        form.read(
            Map.of(
                SlackConfigForm.NAME_BLOCK, typed(SlackConfigForm.NAME_BLOCK, "kimi"),
                SlackConfigForm.BASE_URL_BLOCK,
                    typed(SlackConfigForm.BASE_URL_BLOCK, "https://kimi/v1"),
                SlackConfigForm.MODEL_BLOCK, typed(SlackConfigForm.MODEL_BLOCK, "kimi-k2"),
                SlackConfigForm.TOKEN_BLOCK, typed(SlackConfigForm.TOKEN_BLOCK, "sk-x")));

    assertThat(submission.complete()).isTrue();
    assertThat(submission.builtinByName()).isFalse();
    assertThat(submission.baseUrl()).isEqualTo("https://kimi/v1");
  }

  @Test
  @DisplayName("the dropdown alone is not read as adding a model")
  void switchOnly() {
    final var submission =
        form.read(
            Map.of(SlackConfigForm.ACTIVE_BLOCK, selected(SlackConfigForm.ACTIVE_BLOCK, "kimi")));

    assertThat(submission.active()).isEqualTo("kimi");
    assertThat(submission.adding()).isFalse();
  }

  @Test
  @DisplayName("the model field alone names a built-in model rather than adding an endpoint")
  void builtinByName() {
    final var submission =
        form.read(Map.of(SlackConfigForm.MODEL_BLOCK, typed(SlackConfigForm.MODEL_BLOCK, "o3")));

    assertThat(submission.builtinByName()).isTrue();
    assertThat(submission.complete()).isFalse();
  }

  @Test
  @DisplayName("a blank input counts as not filled in")
  void blanksIgnored() {
    final var submission =
        form.read(Map.of(SlackConfigForm.NAME_BLOCK, typed(SlackConfigForm.NAME_BLOCK, "")));

    assertThat(submission.name()).isNull();
    assertThat(submission.adding()).isFalse();
  }

  @Test
  @DisplayName("how hard to think is a list, and the whole list")
  void effortIsAList() {
    final var values =
        effortOptions(form.blocks(List.of(), null, List.of(), "gpt-4o")).stream()
            .map(OptionObject::getValue)
            .toList();

    // Every effort the SDK knows, plus the two ways of choosing none of them. A user must never
    // have to type one: Spring AI takes it as a bare string and an endpoint fails on a typo.
    assertThat(values).containsAll(ReasoningEfforts.VALUES);
    assertThat(values).contains(SlackConfigForm.EFFORT_INHERIT_OPTION, ReasoningEfforts.NOT_SENT);
  }

  @Test
  @DisplayName("the effort in force is the one preselected")
  void preselectsEffort() {
    final var active = config("kimi").toBuilder().reasoningEffort("high").build();

    final var blocks = form.blocks(List.of(active), active, List.of(), "gpt-4o");

    assertThat(effortElement(blocks).getInitialOption().getValue()).isEqualTo("high");
  }

  @Test
  @DisplayName("a model with no effort of its own shows the application's setting")
  void preselectsInherit() {
    final var blocks = form.blocks(List.of(), null, List.of(), "gpt-4o");

    assertThat(effortElement(blocks).getInitialOption().getValue())
        .isEqualTo(SlackConfigForm.EFFORT_INHERIT_OPTION);
  }

  private static UserModelConfig builtin(final String model) {
    return UserModelConfig.builder().name("@" + model).model(model).build();
  }

  private static StaticSelectElement effortElement(final List<LayoutBlock> blocks) {
    return blocks.stream()
        .filter(InputBlock.class::isInstance)
        .map(InputBlock.class::cast)
        .filter(block -> SlackConfigForm.EFFORT_BLOCK.equals(block.getBlockId()))
        .map(block -> (StaticSelectElement) block.getElement())
        .findFirst()
        .orElseThrow();
  }

  private static List<OptionObject> effortOptions(final List<LayoutBlock> blocks) {
    return effortElement(blocks).getOptions();
  }

  private static String tokenHint(final List<LayoutBlock> blocks) {
    return blocks.stream()
        .filter(InputBlock.class::isInstance)
        .map(InputBlock.class::cast)
        .filter(block -> SlackConfigForm.TOKEN_BLOCK.equals(block.getBlockId()))
        .findFirst()
        .map(block -> block.getHint() == null ? null : block.getHint().getText())
        .orElse(null);
  }

  private static Map<String, ViewValue> typed(final String blockId, final String value) {
    return Map.of(blockId + "_a", new ViewValue(value, null));
  }

  private static Map<String, ViewValue> selected(final String blockId, final String value) {
    return Map.of(blockId + "_a", new ViewValue(null, new ViewValue.SelectedOption(value)));
  }

  private static UserModelConfig config(final String name) {
    return UserModelConfig.builder()
        .name(name)
        .model(name + "-model")
        .baseUrl("https://x/v1")
        .build();
  }

  private static List<OptionObject> options(final List<LayoutBlock> blocks) {
    return blocks.stream()
        .filter(InputBlock.class::isInstance)
        .map(InputBlock.class::cast)
        .filter(block -> SlackConfigForm.ACTIVE_BLOCK.equals(block.getBlockId()))
        .map(block -> ((StaticSelectElement) block.getElement()).getOptions())
        .findFirst()
        .orElseThrow();
  }
}
