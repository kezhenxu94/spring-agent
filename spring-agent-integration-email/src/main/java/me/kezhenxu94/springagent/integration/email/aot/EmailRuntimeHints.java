package me.kezhenxu94.springagent.integration.email.aot;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * What a native image needs to be told about, which for this module is entirely Jakarta Mail.
 *
 * <p>Jakarta Mail resolves a protocol to an implementation at runtime, by reading a table of
 * providers out of the jar and instantiating the named class by reflection. Both halves of that are
 * invisible to a JVM build and both disappear from a native image unless registered here: the
 * resource files vanish, and the class they name is reachable from no code path the analysis can
 * see. The symptom is a binary that starts, connects to nothing, and reports that no provider
 * exists for {@code imaps} — at runtime, from a build that passed.
 *
 * <p>Nothing is registered for the reading of a message. This module takes its payload as a {@code
 * MimeMessage} and its own types are plain records the analysis can follow, so — as {@code
 * EventsRuntimeHints} puts it about the webhook readers — they need none.
 *
 * <p>The triage prompt needs none either. {@code EventsRuntimeHints} registers {@code
 * events/prompts/*.md} as a pattern, and a resource pattern is matched against the whole classpath
 * rather than against the module that registered it, so a source shipping a prompt at the
 * conventional location is already covered.
 */
public class EmailRuntimeHints implements RuntimeHintsRegistrar {

  /**
   * The tables Jakarta Mail reads to find out what implements a protocol.
   *
   * <p>The first is written by the implementation jar, the second by the API jar as a fallback, and
   * the third maps an address type to a protocol. All three are read from the classpath by name at
   * session startup.
   */
  private static final String[] PROVIDER_TABLES = {
    "META-INF/javamail.providers",
    "META-INF/javamail.default.providers",
    "META-INF/javamail.address.map",
    "META-INF/javamail.default.address.map",
    "META-INF/services/jakarta.mail.util.StreamProvider",
    "META-INF/services/jakarta.activation.spi.MailcapRegistryProvider",
    "META-INF/services/jakarta.activation.spi.MimeTypeRegistryProvider",
    "META-INF/mailcap",
    "META-INF/mailcap.default",
    "META-INF/mimetypes.default"
  };

  /**
   * The classes those tables name, registered by string rather than by reference.
   *
   * <p>By string deliberately, the idiom {@code LarkSdkRuntimeHints} uses: naming them in code
   * would be a compile-time dependency on the internals of an implementation this module takes as a
   * provider, and {@code registerTypeIfPresent} is the accompanying half — a provider a deployment
   * did not take is not an error, it is a protocol nobody configured.
   */
  private static final String[] PROVIDERS = {
    "org.eclipse.angus.mail.imap.IMAPStore",
    "org.eclipse.angus.mail.imap.IMAPSSLStore",
    "org.eclipse.angus.mail.imap.IMAPProvider",
    "org.eclipse.angus.mail.imap.IMAPSSLProvider",
    "org.eclipse.angus.mail.handlers.text_plain",
    "org.eclipse.angus.mail.handlers.text_html",
    "org.eclipse.angus.mail.handlers.multipart_mixed",
    "org.eclipse.angus.mail.handlers.message_rfc822",
    "org.eclipse.angus.mail.util.MailStreamProvider",
    "org.eclipse.angus.activation.MailcapRegistryProviderImpl",
    "org.eclipse.angus.activation.MimeTypeRegistryProviderImpl"
  };

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    for (final var table : PROVIDER_TABLES) {
      hints.resources().registerPattern(table);
    }
    for (final var provider : PROVIDERS) {
      hints
          .reflection()
          .registerTypeIfPresent(
              classLoader, provider, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
    }
  }
}
