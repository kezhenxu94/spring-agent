package me.kezhenxu94.springagent.integration.telegram.handler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardButton;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardMarkup;

/**
 * Every button this module draws, and what each one says when pressed.
 *
 * <p>The callback data is the whole of the coupling between a button and the handler that answers
 * it, so the prefixes live here once — the same reason {@code SlackStopButton} is a class. All of
 * them start {@code sa_}, which is what tells this application's buttons from anything else a
 * client might send.
 *
 * <p><b>64 bytes</b> is all Telegram allows a button's data, and it refuses the whole message over
 * one that is longer. So no button carries free text: a question's option is its index, a
 * suggestion is its index, and a model whose name would not fit is left off the keyboard — see
 * {@link #fits}.
 */
public final class TelegramKeyboards {

  /** {@code sa_stop:<run id>}. */
  public static final String STOP = "sa_stop:";

  /** {@code sa_q:<pending id>:<question>:<option>}. */
  public static final String ANSWER = "sa_q:";

  /** {@code sa_qs:<pending id>}, the submit button of a form with more than one choice to make. */
  public static final String SUBMIT = "sa_qs:";

  /** {@code sa_sug:<index>}, a suggestion on the welcome message. */
  public static final String SUGGESTION = "sa_sug:";

  /** {@code sa_cfg:<kind>:<value>}, a choice on the model settings. */
  public static final String CONFIG = "sa_cfg:";

  /** Telegram's own ceiling on a button's data, in bytes. */
  static final int MAX_DATA_BYTES = 64;

  /** What a chosen option is marked with, on a form where choosing does not yet answer. */
  public static final String CHOSEN = "✅ ";

  private TelegramKeyboards() {}

  public static InlineKeyboardButton button(final String text, final String data) {
    return new InlineKeyboardButton(text, data);
  }

  public static boolean fits(final String data) {
    return data.getBytes(StandardCharsets.UTF_8).length <= MAX_DATA_BYTES;
  }

  /** One row per button, the shape a list of choices reads best in on a phone. */
  public static InlineKeyboardMarkup column(final List<InlineKeyboardButton> buttons) {
    final var rows = new ArrayList<List<InlineKeyboardButton>>();
    buttons.forEach(button -> rows.add(List.of(button)));
    return new InlineKeyboardMarkup(rows);
  }

  public static InlineKeyboardMarkup rows(final List<List<InlineKeyboardButton>> rows) {
    return new InlineKeyboardMarkup(List.copyOf(rows));
  }
}
