package me.kezhenxu94.springagent.integration.email;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Who sent a message, where that can be established, and nothing at all where it cannot.
 *
 * <p>A {@code From:} header is a string its author typed. On its own it is no more an identity than
 * the subject line is, which is why an allow-list matched against it would be worse than no
 * allow-list at all: it would take the one part of a message most obviously under an attacker's
 * control and treat it as the thing that decides whether the agent reads the rest. What makes it an
 * identity is a DKIM signature that verified over a domain the address belongs to, and {@link
 * AuthenticationResults} is where that verdict is read from.
 *
 * <p>Returning empty is the ordinary answer for anything that arrived without one, and the intake
 * drops what has no actor as soon as a {@code trusted-actors} list exists — which for this source
 * always does, because {@code MailboxWatcher} refuses to start without one.
 */
@Slf4j
public final class SenderIdentity {

  private SenderIdentity() {}

  /**
   * The sender's address where DKIM vouched for it, empty otherwise.
   *
   * <p>The whole address is returned, local part included, though what DKIM signs is a domain. That
   * is sound only because {@code From} is among the headers a signature covers — DMARC requires it,
   * and every signer in practice includes it — so a verified signature for an aligned domain covers
   * the address as written. A deployment that would rather not lean on that writes its rules
   * against the domain, which is the natural way to write them anyway.
   */
  public static Optional<String> of(
      final Message message, final String authservId, final String header) {
    final var from = fromAddress(message);
    if (from.isEmpty()) {
      log.info("Ignoring a message: it names no single sender in From");
      return Optional.empty();
    }
    final var address = from.get();
    final var domain = domainOf(address);
    if (domain == null) {
      log.info("Ignoring a message from {}: that is not an address with a domain", address);
      return Optional.empty();
    }

    final var headers = headers(message, header);
    final var results = AuthenticationResults.firstIn(headers, authservId);
    if (results.isEmpty()) {
      // The most valuable line in this class, and the reason it is at info rather than debug. A
      // mismatched authserv-id is the one misconfiguration that produces silence rather than an
      // error, and naming what was actually on the message is the whole of what somebody needs to
      // fix it — usually by copying one of these into app.email.authserv-id.
      final var seen = AuthenticationResults.identitiesIn(headers);
      if (seen.isEmpty()) {
        log.info(
            "Ignoring a message from {}: it carries no {} header at all, so nothing has verified"
                + " who sent it. Either the server feeding this mailbox does not check DKIM, or it"
                + " is not the one this mailbox is read from.",
            address,
            header);
      } else {
        log.info(
            "Ignoring a message from {}: none of the {} headers on it were written by '{}', which"
                + " is what app.email.authserv-id says to trust. The message carries {}. If one of"
                + " those is this deployment's own mail server, that is the value to configure.",
            address,
            header,
            authservId,
            seen);
      }
      return Optional.empty();
    }

    final var passes = results.get().dkimPasses();
    if (passes.isEmpty()) {
      log.info(
          "Ignoring a message from {}: {} reported no DKIM signature that verified",
          address,
          authservId);
      return Optional.empty();
    }
    for (final var pass : passes) {
      final var signer = pass.signingDomain();
      if (signer != null && aligned(domain, signer)) {
        log.debug("{} vouched for {}, signed by {}", authservId, address, signer);
        return Optional.of(address);
      }
    }
    log.info(
        "Ignoring a message from {}: {} verified a signature by {}, which does not vouch for the"
            + " domain {}. A signature only speaks for its own domain or one below it.",
        address,
        authservId,
        passes.stream().map(AuthenticationResults.Result::signingDomain).toList(),
        domain);
    return Optional.empty();
  }

  /**
   * Whether a signature by {@code signingDomain} vouches for an address at {@code fromDomain}.
   *
   * <p>The same domain, or the address sitting on a subdomain of the signer — {@code
   * lists.apache.org} signed by {@code apache.org}. Not the other way about: a signature by {@code
   * lists.apache.org} says nothing about {@code apache.org}, and accepting it would let anybody who
   * can publish a key under a subdomain speak for the parent.
   *
   * <p>Stricter than DMARC's relaxed alignment, which compares organizational domains and so needs
   * the public suffix list to know that {@code example.co.uk} is one name and not a subdomain of
   * {@code co.uk}. Carrying a copy of that list — and keeping it current — to admit a few more
   * messages is not a trade worth making; erring towards refusal here costs a deployment one more
   * pattern in its list, and erring the other way costs it the allow-list.
   */
  static boolean aligned(final String fromDomain, final String signingDomain) {
    final var signer = signingDomain.trim().toLowerCase(Locale.ROOT);
    return fromDomain.equals(signer) || fromDomain.endsWith("." + signer);
  }

  /**
   * The one address in {@code From}, lowercased, or empty.
   *
   * <p>Empty where there is more than one. A {@code From} naming two people is legal, vanishingly
   * rare, and has no single answer to "who sent this" — and picking the first would let a message
   * name a trusted sender alongside whoever actually wrote it.
   */
  private static Optional<String> fromAddress(final Message message) {
    try {
      final var from = message.getFrom();
      if (from == null || from.length != 1 || !(from[0] instanceof InternetAddress internet)) {
        return Optional.empty();
      }
      final var address = internet.getAddress();
      return address == null || address.isBlank()
          ? Optional.empty()
          : Optional.of(address.trim().toLowerCase(Locale.ROOT));
    } catch (MessagingException e) {
      // An unparseable From is not an error worth a stack trace: it is a message from nobody, and
      // the caller already treats that as untrusted.
      log.debug("Could not read From", e);
      return Optional.empty();
    }
  }

  private static String domainOf(final String address) {
    final var at = address.lastIndexOf('@');
    return at < 0 || at == address.length() - 1 ? null : address.substring(at + 1);
  }

  private static String[] headers(final Message message, final String header) {
    try {
      return message.getHeader(header);
    } catch (MessagingException e) {
      log.debug("Could not read {}", header, e);
      return null;
    }
  }
}
