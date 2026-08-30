package me.kezhenxu94.springagent.appcli;

import java.util.Locale;
import lombok.Getter;
import me.kezhenxu94.springagent.appcli.config.CliProperties;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/** Everything the command line says to the user, in the user's language. */
@Component
public class CliMessages {

  private final MessageSource messageSource;

  @Getter private final Locale locale;

  public CliMessages(final MessageSource messageSource, final CliProperties properties) {
    this.messageSource = messageSource;
    this.locale = properties.locale() == null ? Locale.getDefault() : properties.locale();
  }

  public String get(final String key, final Object... arguments) {
    return messageSource.getMessage(key, arguments, key, locale);
  }
}
