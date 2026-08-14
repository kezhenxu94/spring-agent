package me.kezhenxu94.springagent.integration.feishu.config;

import com.google.common.base.Strings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.SneakyThrows;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Feishu tenant and application credentials. */
@ConfigurationProperties(prefix = "app.feishu")
public record FeishuProperties(
    String encryptKey,
    String tenantId,
    String tenantDomain,
    String appId,
    String appSecret,
    String botOpenId,
    String verificationToken,
    CardText cardText,
    QuestionText questionText) {

  public FeishuProperties {
    if (cardText == null) {
      cardText = new CardText(null, null, null, null, null, null, null, null, null);
    }
    if (questionText == null) {
      questionText = new QuestionText(null, null, null, null, null, null, null, null, null, null);
    }
  }

  /**
   * Substitution happens in a template's text rather than its parsed form, so a label carrying a
   * quote or a newline would otherwise produce a card that no longer parses.
   */
  static String jsonEscaped(final String value) {
    final var escaped = new StringBuilder(value.length());
    for (final var c : value.toCharArray()) {
      switch (c) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (c < 0x20) {
            escaped.append(String.format("\\u%04x", (int) c));
          } else {
            escaped.append(c);
          }
        }
      }
    }
    return escaped.toString();
  }

  /**
   * Everything in a reply card that a reader sees and the model did not write. Configurable because
   * the rest of a card is in whatever language the user wrote in, and these would otherwise be the
   * only English in it.
   *
   * <p>The first three fill the {@code {generating}}, {@code {stop}} and {@code {conversationHint}}
   * placeholders of the card template, so writing the card in another language is a matter of
   * setting these rather than shipping a whole card of your own. The rest are written into a card
   * while the run is under way.
   *
   * @param generating shown in place of the answer until the first of it arrives
   * @param stop label of the button that cancels the run
   * @param conversationHint footer explaining how to carry the conversation on
   * @param errorFormat shown in place of the answer when the run fails, over {@code {error}}
   * @param unknownError stands in for {@code {error}} when the failure carries no message
   * @param callingTool shown while a tool runs, over {@code {tool}}
   * @param imageUnavailable stands in for an image that could not be uploaded to Feishu
   * @param todoHeading heading above the agent's todo list
   * @param stopNotYours toast for someone other than the person whose run it is pressing stop.
   *     Cards are shared, so in a group chat everyone can see and press that button.
   */
  public record CardText(
      String generating,
      String stop,
      String conversationHint,
      String errorFormat,
      String unknownError,
      String callingTool,
      String imageUnavailable,
      String todoHeading,
      String stopNotYours) {

    public CardText {
      if (Strings.isNullOrEmpty(generating)) {
        generating = "Generating...";
      }
      if (Strings.isNullOrEmpty(stop)) {
        stop = "Stop";
      }
      if (Strings.isNullOrEmpty(conversationHint)) {
        conversationHint =
            "Reply to this message to carry on the conversation; send a new message to start a"
                + " fresh one";
      }
      if (Strings.isNullOrEmpty(errorFormat)) {
        errorFormat = "Something went wrong: {error}";
      }
      if (Strings.isNullOrEmpty(unknownError)) {
        unknownError = "Unknown error";
      }
      if (Strings.isNullOrEmpty(callingTool)) {
        callingTool = "Calling {tool} ...";
      }
      if (Strings.isNullOrEmpty(imageUnavailable)) {
        imageUnavailable = "(image unavailable)";
      }
      if (Strings.isNullOrEmpty(todoHeading)) {
        todoHeading = "**To do**";
      }
      if (Strings.isNullOrEmpty(stopNotYours)) {
        stopNotYours = "Only the person who started this run can stop it";
      }
    }

    /**
     * Fills a card template's label placeholders. A template that spells its labels out instead is
     * left as it is, so a deployment can still ship a card of its own and ignore these.
     */
    public String render(final String cardJson) {
      return cardJson
          .replace("{generating}", jsonEscaped(generating))
          .replace("{stop}", jsonEscaped(stop))
          .replace("{conversationHint}", jsonEscaped(conversationHint));
    }

    public String error(final String message) {
      return errorFormat.replace(
          "{error}", Strings.isNullOrEmpty(message) ? unknownError : message);
    }

    public String callingTool(final String toolName) {
      return callingTool.replace("{tool}", Strings.nullToEmpty(toolName));
    }
  }

  /**
   * Everything in the agent's question form that a reader sees and the model did not write. Split
   * from {@link CardText} because these belong to a different template, {@code question-form.json},
   * and because a deployment that turns the tool off never sees any of them.
   *
   * <p>The first three fill that template's placeholders; the rest are toasts and prose written
   * when an answer comes back.
   *
   * @param selectHint placeholder in the dropdown that picks one of a question's options
   * @param otherHint placeholder in the free-text box offered alongside every question
   * @param submitText label of the button that sends the answers
   * @param submitted toast confirming the answers were taken
   * @param alreadyAnswered toast for a form submitted twice
   * @param superseded said in place of a form whose questions a later message in the conversation
   *     overtook, on the card and in the toast if it is somehow submitted anyway
   * @param expired toast for a form submitted past the questions' lifetime
   * @param notYours toast for someone other than the person who was asked. Cards are shared, so in
   *     a group chat everyone can see and press this form.
   * @param nothingChosen toast for a submission that answered none of the questions
   * @param answerHeading opens the message the answers are carried back to the agent in
   */
  public record QuestionText(
      String selectHint,
      String otherHint,
      String submitText,
      String submitted,
      String alreadyAnswered,
      String superseded,
      String expired,
      String notYours,
      String nothingChosen,
      String answerHeading) {

    public QuestionText {
      if (Strings.isNullOrEmpty(selectHint)) {
        selectHint = "Pick an option";
      }
      if (Strings.isNullOrEmpty(otherHint)) {
        otherHint = "Other — type your own answer";
      }
      if (Strings.isNullOrEmpty(submitText)) {
        submitText = "Submit";
      }
      if (Strings.isNullOrEmpty(submitted)) {
        submitted = "Thanks — picking this up now";
      }
      if (Strings.isNullOrEmpty(alreadyAnswered)) {
        alreadyAnswered = "These questions have already been answered";
      }
      if (Strings.isNullOrEmpty(superseded)) {
        superseded = "_You answered in a message instead, so this form is closed._";
      }
      if (Strings.isNullOrEmpty(expired)) {
        expired = "These questions are too old to answer; just say what you need in a new message";
      }
      if (Strings.isNullOrEmpty(notYours)) {
        notYours = "Only the person who was asked can answer these";
      }
      if (Strings.isNullOrEmpty(nothingChosen)) {
        nothingChosen = "Choose an option or type an answer first";
      }
      if (Strings.isNullOrEmpty(answerHeading)) {
        answerHeading = "Here are my answers to the questions you asked:";
      }
    }

    /** Fills the question form template's label placeholders. */
    public String render(final String formJson) {
      return formJson
          .replace("{selectHint}", jsonEscaped(selectHint))
          .replace("{otherHint}", jsonEscaped(otherHint))
          .replace("{submitText}", jsonEscaped(submitText));
    }
  }

  @SneakyThrows
  public byte[] encryptKeyBytes() {
    final var digest = MessageDigest.getInstance("SHA-256");
    return digest.digest(encryptKey.getBytes(StandardCharsets.UTF_8));
  }
}
