package me.kezhenxu94.springagent.integration.telegram.api;

import java.time.Duration;
import java.util.Locale;
import lombok.Getter;

/**
 * Telegram refused a call, or could not be reached to make it.
 *
 * <p><b>The message never carries the request URL.</b> The bot token is part of every Bot API path,
 * and a client exception that names the URL — which is what RestClient's do — puts the token into
 * the log and, through a tool's result, in front of the model. So everything that reaches here says
 * which method failed and why, and nothing else.
 */
@Getter
public class TelegramApiException extends RuntimeException {

  /** Telegram's own code, or 0 where Telegram was never reached. */
  private final int errorCode;

  /** What Telegram said, as it said it — {@code Bad Request: message is not modified}, say. */
  private final String description;

  /** How long Telegram asked to be left alone, where it answered a burst with 429. */
  private final Duration retryAfter;

  public TelegramApiException(
      final String method,
      final int errorCode,
      final String description,
      final Duration retryAfter) {
    super(method + " failed: " + (errorCode == 0 ? "" : errorCode + " ") + description);
    this.errorCode = errorCode;
    this.description = description == null ? "" : description;
    this.retryAfter = retryAfter;
  }

  public TelegramApiException(final String method, final Throwable cause) {
    // The cause's class only: its message is where the URL, and so the token, would be.
    super(method + " failed: could not reach Telegram (" + cause.getClass().getSimpleName() + ")");
    this.errorCode = 0;
    this.description = "";
    this.retryAfter = null;
  }

  public boolean rateLimited() {
    return errorCode == 429;
  }

  /**
   * An edit that would leave the message as it is, which Telegram refuses rather than ignores. Not
   * a failure here: a write carrying the same state twice is the coalescing working.
   */
  public boolean notModified() {
    return describes("message is not modified");
  }

  /** The text is over Telegram's limit for one message. */
  public boolean tooLong() {
    return describes("message is too long") || describes("text is too long");
  }

  /** Another process is polling the same bot, which only one may do at a time. */
  public boolean conflict() {
    return errorCode == 409;
  }

  /** The bot token was refused outright. */
  public boolean unauthorized() {
    return errorCode == 401 || errorCode == 404;
  }

  private boolean describes(final String fragment) {
    return description.toLowerCase(Locale.ROOT).contains(fragment);
  }
}
