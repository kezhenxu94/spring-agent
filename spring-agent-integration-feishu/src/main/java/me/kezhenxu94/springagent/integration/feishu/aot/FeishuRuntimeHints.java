package me.kezhenxu94.springagent.integration.feishu.aot;

import java.util.List;
import me.kezhenxu94.springagent.integration.feishu.model.AudioMessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.FeishuResponse;
import me.kezhenxu94.springagent.integration.feishu.model.FileMessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.ImageMessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.MediaMessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.MessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.MessageContentDeserializer;
import me.kezhenxu94.springagent.integration.feishu.model.PostMessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.TextMessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.spreadsheet.GetProtectedRangesDTO;
import me.kezhenxu94.springagent.integration.feishu.model.spreadsheet.GetValueRangeBatchDTOV2;
import me.kezhenxu94.springagent.integration.feishu.model.spreadsheet.GetValueRangeDTO;
import me.kezhenxu94.springagent.integration.feishu.model.spreadsheet.GetValueRangeDTOV2;
import me.kezhenxu94.springagent.integration.feishu.sheet.FeishuSheetsService;
import me.kezhenxu94.springagent.integration.feishu.sheet.ValueRange;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.ResolvableType;
import tools.jackson.databind.JsonNode;

/**
 * Binding hints for the Feishu payloads whose Jackson types are chosen at runtime, and which the
 * closed-world analysis therefore cannot reach from the bytecode.
 *
 * <p>The DTOs that appear directly in a {@code RestTemplate} call are already covered by
 * {@code @RegisterReflectionForBinding} on the services that make the call; this covers what those
 * annotations cannot express.
 */
public class FeishuRuntimeHints implements RuntimeHintsRegistrar {

  private final BindingReflectionHintsRegistrar binding = new BindingReflectionHintsRegistrar();

  /**
   * Every concrete payload reached through {@code FeishuSheetsService.readResponse(raw, dataType)},
   * which builds {@code FeishuResponse<dataType>} with {@code constructParametricType}. The type
   * argument arrives in a variable, so only the call sites name these.
   */
  private static final List<Class<?>> RESPONSE_PAYLOADS =
      List.of(
          FeishuSheetsService.Sheets.class,
          JsonNode.class,
          Object.class,
          GetProtectedRangesDTO.class,
          GetValueRangeDTO.class,
          GetValueRangeDTOV2.class,
          GetValueRangeBatchDTOV2.class);

  /**
   * {@code MessageContentDeserializer} selects among these by which fields are present, so no
   * bytecode reference leads from {@link MessageContent} to any of them.
   */
  private static final List<Class<?>> MESSAGE_CONTENTS =
      List.of(
          MessageContent.class,
          TextMessageContent.class,
          PostMessageContent.class,
          PostMessageContent.ContentElement.class,
          ImageMessageContent.class,
          FileMessageContent.class,
          AudioMessageContent.class,
          MediaMessageContent.class);

  /**
   * The card templates, each read through a {@code @Value("classpath:/feishu/...")} that gets no
   * hint of its own.
   */
  private static final List<String> CARD_TEMPLATES =
      List.of(
          "feishu/reply-card.json",
          "feishu/card-elements.json",
          "feishu/question-form.json",
          "feishu/subagent-panel.json",
          "feishu/welcome-card.json",
          "feishu/update-card.json");

  /** The cards' own words, whose locale is only known when the binary runs. */
  private static final String MESSAGES_BUNDLE = "feishu.messages";

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    CARD_TEMPLATES.forEach(hints.resources()::registerPattern);
    hints.resources().registerResourceBundle(MESSAGES_BUNDLE);

    // The reference pages three of the tools return as their whole result, and each tool's own
    // description, both in whichever language the deployment configured. Read while a run is being
    // answered, so an image without them fails the call rather than losing a paragraph.
    hints.resources().registerPattern("feishu/prompts/*.md");
    hints.resources().registerPattern("feishu/prompts/tools/*.md");

    // And each tool parameter's description. A plain resource pattern rather than a resource
    // bundle:
    // ModuleToolTexts reads these as resources precisely so as not to go through a ResourceBundle,
    // which would consult the host's locale ahead of the base file.
    hints.resources().registerPattern("feishu/tools.properties");
    hints.resources().registerPattern("feishu/tools_*.properties");

    // The greeting and the numbered update notes, in every language they ship in. FeishuUpdates
    // reads each by the name it must have rather than listing the directory — see the comment
    // there — so a pattern that makes them readable is all the binary needs.
    hints.resources().registerPattern("feishu/welcome*.md");
    hints.resources().registerPattern("feishu/updates/*.md");

    // The parameterized form, not the raw class: it is the generic type that carries the payload's
    // own property hints down from FeishuResponse#data.
    for (final Class<?> payload : RESPONSE_PAYLOADS) {
      binding.registerReflectionHints(
          hints.reflection(),
          ResolvableType.forClassWithGenerics(FeishuResponse.class, payload).getType());
    }
    binding.registerReflectionHints(hints.reflection(), FeishuSheetsService.SheetSummary.class);

    for (final Class<?> content : MESSAGE_CONTENTS) {
      binding.registerReflectionHints(hints.reflection(), content);
      registerLombokBuilder(hints, classLoader, content);
    }

    for (final Class<?> type :
        List.of(
            ValueRange.class,
            ValueRange.Range.class,
            ValueRange.CellValue.class,
            ValueRange.FormattedValues.class,
            ValueRange.FormattedValue.class,
            ValueRange.FormattedValue.SegmentStyle.class)) {
      binding.registerReflectionHints(hints.reflection(), type);
    }

    // Jackson instantiates these by the class named in the annotation, never by a direct reference.
    hints
        .reflection()
        .registerType(
            MessageContentDeserializer.class, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
    for (final String serde :
        List.of(
            "me.kezhenxu94.springagent.integration.feishu.sheet.ValueRange$Range$Serializer",
            "me.kezhenxu94.springagent.integration.feishu.sheet.ValueRange$Range$Deserializer",
            "me.kezhenxu94.springagent.integration.feishu.sheet.ValueRange$CellValue$Serializer",
            "me.kezhenxu94.springagent.integration.feishu.sheet.ValueRange$CellValue$Deserializer")) {
      hints
          .reflection()
          .registerTypeIfPresent(classLoader, serde, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
    }
  }

  /**
   * Lombok's {@code @Builder}/{@code @Jacksonized} makes Jackson bind through a generated nested
   * builder class, which is a distinct class and so needs a hint of its own — the same pairing
   * {@code FeishuTenantAccessTokenService} already spells out by hand for {@code
   * TenantAccessToken}.
   */
  private void registerLombokBuilder(
      final RuntimeHints hints, final ClassLoader classLoader, final Class<?> type) {
    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader,
            type.getName() + "$" + type.getSimpleName() + "Builder",
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.ACCESS_DECLARED_FIELDS);
  }
}
