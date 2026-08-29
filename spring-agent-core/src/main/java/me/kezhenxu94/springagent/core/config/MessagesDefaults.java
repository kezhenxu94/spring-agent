package me.kezhenxu94.springagent.core.config;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

/**
 * Adds {@link CoreMessages#BASENAME} to {@code spring.messages.basename}, so that the notes the
 * agent writes into a conversation itself resolve in an application that merely put core on its
 * classpath.
 *
 * <p>{@link CoreMessages} reads through the application's own {@link
 * org.springframework.context.MessageSource} rather than building one, so that encoding, caching
 * and the system-locale fallback are configured once under {@code spring.messages}. The cost of
 * that is a bundle the application has to name, and an application that does not name it loses
 * nothing at startup — every message comes back as its own key, which the model then reads as the
 * note. This is what removes the requirement.
 *
 * <p><b>Appended, not defaulted, which is why this is the one {@code *Defaults} here that does not
 * contribute the lowest-precedence source.</b> The property holds a list and an application setting
 * it <em>replaces</em> what came before, so a default under it would be dropped by any application
 * with a bundle of its own — precisely the applications that have one to lose. The value written
 * here is instead derived from whatever the application already said, with core's basename added to
 * the end, so nothing it configured is overridden: what wins is still its own list, plus one entry
 * it cannot have meant to exclude.
 *
 * <p>Absent means Boot's own {@code messages}, which is carried over rather than replaced — an
 * application relying on that default keeps its bundle — but only when that bundle is actually
 * there. A basename with no bundle behind it is not the harmless no-op it looks: {@code
 * ResourceBundleMessageSource} swallows the miss only on its cache-forever path, so under any
 * locale the JVM does not list, the first message looked up throws {@code MissingResourceException}
 * instead. Writing {@code messages} into the list on behalf of an application that never had such a
 * file would break every message in it, core's included. The probe below is the same one Boot's own
 * {@code MessageSourceAutoConfiguration} makes before it creates the message source at all.
 *
 * <p>Already naming core's basename means there is nothing to do, and no property source is added
 * at all, so an application that wants core's messages resolved before its own can say so and be
 * left alone. What it does name is left exactly as written, missing bundles and all: that list is
 * its own business, and was before this existed.
 *
 * <p>A module with messages of its own does not join the list. It ships a message source of its
 * own, as {@code FeishuMessages} does, because two modules claiming one basename would be a fight
 * over which one wins — this is core's bundle, and core is the one thing every application here has
 * already got.
 */
public class MessagesDefaults implements EnvironmentPostProcessor, Ordered {

  static final String BASENAME = "spring.messages.basename";

  /**
   * What {@code MessageSourceProperties} uses when the property is absent. Repeated rather than
   * read, there being nothing to read it from at this point, and pinned by {@code
   * MessagesDefaultsTest} against the version this builds with.
   */
  static final String BOOT_DEFAULT = "messages";

  private final Predicate<String> bundleExists;

  public MessagesDefaults() {
    this(MessagesDefaults::onClasspath);
  }

  /**
   * Takes the probe, so that both branches of the one decision this makes can be tested in a JVM
   * whose classpath is what it is: whether a bundle is there is the input, not something a test can
   * arrange.
   */
  MessagesDefaults(final Predicate<String> bundleExists) {
    this.bundleExists = bundleExists;
  }

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    final var configured = configured(environment);
    if (names(configured)) {
      return;
    }
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "springAgentMessagesDefaults", Map.of(BASENAME, appended(configured))));
  }

  /**
   * What the list holds before this appends to it: what the application said, or Boot's default
   * where it said nothing and that default resolves to a bundle, or nothing at all.
   */
  private String configured(final ConfigurableEnvironment environment) {
    final var value = environment.getProperty(BASENAME);
    if (value != null) {
      return value;
    }
    return bundleExists.test(BOOT_DEFAULT) ? BOOT_DEFAULT : "";
  }

  /** Whether a bundle answers to {@code basename}, as Boot asks it of the same name. */
  private static boolean onClasspath(final String basename) {
    final var classLoader = ClassUtils.getDefaultClassLoader();
    final var resource = basename.replace('.', '/') + ".properties";
    return classLoader != null
        ? classLoader.getResource(resource) != null
        : MessagesDefaults.class.getResource("/" + resource) != null;
  }

  /** Whether {@code configured} already names core's bundle. */
  private static boolean names(final String configured) {
    return Arrays.stream(configured.split(","))
        .map(String::trim)
        .anyMatch(CoreMessages.BASENAME::equals);
  }

  private static String appended(final String configured) {
    // Nothing to append to — an emptied property, or an application with no bundle of its own —
    // leaves core's alone rather than writing a leading comma, which the binder would read as an
    // empty basename and the message source would then look a bundle up under.
    return configured.isBlank() ? CoreMessages.BASENAME : configured + "," + CoreMessages.BASENAME;
  }

  /**
   * Last, so that the property sources this reads include the ones config data loaded from {@code
   * application.yaml} — this appends to what the application said, so it has to have been said.
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
