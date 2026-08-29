package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.MessageSourceProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * What has to hold is one thing said two ways: core's bundle always ends up in the list, and
 * nothing the application put there ever leaves it.
 */
class MessagesDefaultsTest {

  /** An application with a bundle of its own under Boot's default name. */
  private final MessagesDefaults defaults = new MessagesDefaults(name -> true);

  /** One without, which is the ordinary SDK consumer. */
  private final MessagesDefaults noBundleOfItsOwn = new MessagesDefaults(name -> false);

  @Test
  @DisplayName("an application that says nothing keeps Boot's bundle and gains core's")
  void shouldAppendToBootsDefault() {
    final var environment = new StandardEnvironment();

    defaults.postProcessEnvironment(environment, null);

    // Boot's own default carried over rather than replaced: an application relying on it has a
    // messages.properties this would otherwise stop resolving.
    assertThat(environment.getProperty(MessagesDefaults.BASENAME))
        .isEqualTo("messages," + CoreMessages.BASENAME);
  }

  @Test
  @DisplayName("an application with no bundle of its own is not given a name for one")
  void shouldNotNameABundleThatIsNotThere() {
    final var environment = new StandardEnvironment();

    noBundleOfItsOwn.postProcessEnvironment(environment, null);

    // Not "messages,core/messages". A basename with no bundle behind it throws
    // MissingResourceException on the first lookup under any locale the JVM does not list, which
    // would take core's messages down with it — CoreMessagesResolveTest is where that is proven
    // against a real context.
    assertThat(environment.getProperty(MessagesDefaults.BASENAME)).isEqualTo(CoreMessages.BASENAME);
  }

  @Test
  @DisplayName("that default is still the one Boot uses")
  void shouldPinBootsDefault() {
    // Read from the properties class rather than trusted: the value above is a copy, and a Boot
    // upgrade that changed it would otherwise drop an application's bundle silently.
    assertThat(new MessageSourceProperties().getBasename())
        .containsExactly(MessagesDefaults.BOOT_DEFAULT);
  }

  @Test
  @DisplayName("an application's own bundles are kept, with core's added after them")
  void shouldAppendToWhatTheApplicationConfigured() {
    final var environment = environmentWith("messages,my/messages");

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(MessagesDefaults.BASENAME))
        .isEqualTo("messages,my/messages," + CoreMessages.BASENAME);
  }

  @Test
  @DisplayName("an application that already names core's bundle is left alone")
  void shouldLeaveAnApplicationThatNamesItAlone() {
    final var environment = environmentWith(CoreMessages.BASENAME + ", messages");
    final var sources = environment.getPropertySources().size();

    defaults.postProcessEnvironment(environment, null);

    // Order is the application's to decide, so this must not be rewritten into the canonical one.
    assertThat(environment.getProperty(MessagesDefaults.BASENAME))
        .isEqualTo(CoreMessages.BASENAME + ", messages");
    assertThat(environment.getPropertySources()).hasSize(sources);
  }

  @Test
  @DisplayName("an application that emptied the property gets core's bundle and no stray comma")
  void shouldNotWriteALeadingComma() {
    final var environment = environmentWith("");

    defaults.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty(MessagesDefaults.BASENAME)).isEqualTo(CoreMessages.BASENAME);
  }

  @Test
  @DisplayName("the value is one the message source reads back as a list of basenames")
  void shouldWriteAListTheBinderReads() {
    final var environment = environmentWith("my/messages");

    defaults.postProcessEnvironment(environment, null);

    // Bound rather than read as a string, since binding is what the application does and it is
    // the step that says the written form is one the binder reads back as two basenames.
    final var properties =
        new Binder(
                ConfigurationPropertySources.get(environment),
                null,
                ApplicationConversionService.getSharedInstance())
            .bind("spring.messages", MessageSourceProperties.class)
            .get();
    assertThat(properties.getBasename()).containsExactly("my/messages", CoreMessages.BASENAME);
  }

  private static StandardEnvironment environmentWith(final String basename) {
    final var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of(MessagesDefaults.BASENAME, basename)));
    return environment;
  }
}
