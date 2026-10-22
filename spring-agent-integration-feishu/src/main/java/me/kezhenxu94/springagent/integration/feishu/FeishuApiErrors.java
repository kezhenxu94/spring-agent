package me.kezhenxu94.springagent.integration.feishu;

import com.google.common.base.Strings;
import com.lark.oapi.core.response.BaseResponse;
import java.util.StringJoiner;

/**
 * Everything Feishu said about a call it refused, as one piece of text for whoever has to act on
 * it.
 *
 * <p>{@code msg} alone is the headline: for a card it is routinely "invalid card content" and no
 * more, while the reason — the property the schema does not know, the row a table did not declare —
 * is in {@code error.field_violations}. A model told only the headline resends the same card; told
 * the field, it fixes it. The troubleshooter link and log id are there for the person reading the
 * conversation, who can take them to Feishu.
 *
 * <p>Nothing is added to what Feishu said. A table of codes and advice would be a second account of
 * Feishu's behaviour, wrong the first time Feishu changes a message, and the text Feishu sends is
 * already specific enough for a model to act on.
 */
public final class FeishuApiErrors {

  private FeishuApiErrors() {}

  public static String describe(final BaseResponse<?> response) {
    final var out = new StringJoiner("\n");
    out.add("code " + response.getCode() + ": " + Strings.nullToEmpty(response.getMsg()));
    final var error = response.getError();
    if (error == null) {
      return out.toString();
    }
    if (!Strings.isNullOrEmpty(error.getMessage())
        && !error.getMessage().equals(response.getMsg())) {
      out.add(error.getMessage());
    }
    if (error.getFieldViolations() != null) {
      for (final var violation : error.getFieldViolations()) {
        final var line = new StringBuilder("- ").append(violation.getField());
        if (!Strings.isNullOrEmpty(violation.getValue())) {
          line.append(" = ").append(violation.getValue());
        }
        if (!Strings.isNullOrEmpty(violation.getDescription())) {
          line.append(": ").append(violation.getDescription());
        }
        out.add(line);
      }
    }
    if (error.getPermissionViolations() != null) {
      for (final var violation : error.getPermissionViolations()) {
        out.add(
            "- "
                + Strings.nullToEmpty(violation.getSubject())
                + ": "
                + Strings.nullToEmpty(violation.getDescription()));
      }
    }
    if (error.getHelps() != null) {
      for (final var help : error.getHelps()) {
        out.add(
            Strings.nullToEmpty(help.getDescription()) + " " + Strings.nullToEmpty(help.getUrl()));
      }
    }
    if (!Strings.isNullOrEmpty(error.getTroubleshooter())) {
      out.add(error.getTroubleshooter());
    }
    if (!Strings.isNullOrEmpty(error.getLogId())) {
      out.add("log_id: " + error.getLogId());
    }
    return out.toString();
  }
}
