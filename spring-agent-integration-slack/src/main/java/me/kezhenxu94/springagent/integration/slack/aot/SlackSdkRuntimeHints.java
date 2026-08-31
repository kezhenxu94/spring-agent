package me.kezhenxu94.springagent.integration.slack.aot;

import java.util.List;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Reflection hints for the Slack SDK's model types.
 *
 * <p>The SDK binds every request and response with Gson, which reaches a class's fields
 * reflectively and never by a reference the analysis can follow. Registering the whole of {@code
 * com.slack.api.model} would be simplest and is what a first attempt reaches for; it is also some
 * thousands of classes for services this application never calls, so the roots are named here and
 * walked transitively instead — the same arrangement {@code LarkSdkRuntimeHints} arrived at.
 *
 * <p>Named as strings and registered with {@code registerTypeIfPresent}, so that an SDK upgrade
 * that moves or removes one of these is a hint that quietly does nothing rather than a build that
 * will not compile. The test beside this class is what notices instead.
 */
public class SlackSdkRuntimeHints implements RuntimeHintsRegistrar {

  /**
   * The payloads this module actually binds: what an event carries, what a Web API call returns,
   * and the Block Kit elements a message is built out of.
   */
  static final List<String> ROOTS =
      List.of(
          // Events, as Socket Mode delivers them.
          "com.slack.api.model.event.MessageEvent",
          "com.slack.api.model.event.MessageFileShareEvent",
          "com.slack.api.model.event.AppHomeOpenedEvent",
          "com.slack.api.app_backend.events.payload.EventsApiPayload",
          "com.slack.api.app_backend.interactive_components.payload.BlockActionPayload",
          // What a message is made of.
          "com.slack.api.model.block.LayoutBlock",
          "com.slack.api.model.block.SectionBlock",
          "com.slack.api.model.block.ContextBlock",
          "com.slack.api.model.block.ActionsBlock",
          "com.slack.api.model.block.DividerBlock",
          "com.slack.api.model.block.HeaderBlock",
          "com.slack.api.model.block.InputBlock",
          "com.slack.api.model.block.composition.PlainTextObject",
          "com.slack.api.model.block.composition.MarkdownTextObject",
          "com.slack.api.model.block.composition.OptionObject",
          "com.slack.api.model.block.element.ButtonElement",
          "com.slack.api.model.block.element.StaticSelectElement",
          "com.slack.api.model.block.element.RadioButtonsElement",
          "com.slack.api.model.block.element.PlainTextInputElement",
          // The calls this module makes.
          "com.slack.api.methods.response.chat.ChatPostMessageResponse",
          "com.slack.api.methods.response.chat.ChatUpdateResponse",
          "com.slack.api.methods.response.chat.ChatPostEphemeralResponse",
          "com.slack.api.methods.response.reactions.ReactionsAddResponse",
          "com.slack.api.methods.response.users.UsersInfoResponse",
          "com.slack.api.methods.response.conversations.ConversationsListResponse",
          "com.slack.api.methods.response.conversations.ConversationsMembersResponse",
          "com.slack.api.methods.response.conversations.ConversationsHistoryResponse",
          "com.slack.api.methods.response.conversations.ConversationsRepliesResponse",
          "com.slack.api.methods.response.files.FilesUploadV2Response",
          "com.slack.api.model.File",
          "com.slack.api.model.Message",
          "com.slack.api.model.User",
          "com.slack.api.model.Conversation",
          // What conversations.list returns beside the channels, which pagination reads.
          "com.slack.api.model.ResponseMetadata");

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    for (final var type : ROOTS) {
      hints
          .reflection()
          .registerTypeIfPresent(
              classLoader,
              type,
              MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
              MemberCategory.INVOKE_DECLARED_METHODS,
              MemberCategory.ACCESS_DECLARED_FIELDS);
    }
  }
}
