package me.kezhenxu94.springagent.integration.websocket.run;

import com.google.common.base.Strings;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.dao.models.ChatSession;
import me.kezhenxu94.springagent.core.notify.Notifier;
import me.kezhenxu94.springagent.core.observing.Route;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import me.kezhenxu94.springagent.integration.websocket.security.WebUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Carrying an answer written here onto the chat surface beside this page.
 *
 * <p>The case: somebody's conversation started in a Feishu group, they pick it up in the browser,
 * and the rest of the group is still watching in Feishu. Without this the conversation simply goes
 * quiet there, which reads as the agent having stopped rather than as the question having moved.
 *
 * <p><b>No SPI of its own.</b> This is core's {@link Notifier} — "say something to a chat with no
 * run behind it" is exactly the shape needed, both chat integrations already implement it, and a
 * deployment has at most one, which is what makes "the chat surface beside this page" a thing that
 * can be resolved at all. So a surface gets mirrored to by implementing nothing new, and a
 * deployment carrying no chat surface has no {@link Notifier}, no mirror and no button offering
 * one.
 *
 * <p><b>Per-request rather than a {@code @Bean AgentResponseListener}.</b> Mirroring is something a
 * person asked for on one message, so it belongs to that request — which is what {@code
 * AgentRequest.listeners} is for. A bean would claim every run and then have to work out which ones
 * it was wanted for, from state that would have to be persisted to be readable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMirrors {

  private final ObjectProvider<Notifier> notifiers;
  private final WebMessages messages;
  private final WebProperties properties;

  /** Which chat platform an answer can be mirrored to, or null where there is none. */
  public String surface() {
    final var notifier = notifiers.getIfAvailable();
    if (notifier == null) {
      return null;
    }
    final var surface = notifier.surface();
    // A notifier that will not name itself cannot be offered as a button, since the button is the
    // platform's own icon. It still works for everything else a notifier is for.
    return Strings.isNullOrEmpty(surface) ? null : surface;
  }

  /**
   * A listener that mirrors this run's answer onto the chat {@code conversation} belongs to, or
   * null where there is nowhere to put it — no chat surface installed, or no chat this conversation
   * can be traced to.
   *
   * @param conversation the row the run belongs to, which is where the route comes from
   * @param asker who is sending, for the attribution line and for the tenant check
   * @param question what they typed, quoted in the card so the answer is not left without one
   */
  public AgentResponseListener forRun(
      final ChatSession conversation, final WebUser asker, final String question) {
    final var notifier = notifiers.getIfAvailable();
    if (notifier == null || conversation == null || asker == null) {
      return null;
    }
    final var route = routeTo(conversation, asker);
    if (route == null) {
      return null;
    }
    final var prelude =
        prelude(conversation, asker, question, notifier, !route.groupId().isEmpty());
    // The conversation id, offered as the message to thread the card under. For a conversation that
    // began on the chat surface it *is* that surface's root message id — the surface set
    // conversationId and rootMessageId to the same value — so a mirrored answer lands in the thread
    // the question was asked in rather than loose at the bottom of the chat. For one that began in
    // the page it is a UUID and names no message, which the notifier recognises and falls back
    // from. Passed rather than inspected here: what a message identifier looks like is the
    // surface's business, not this module's.
    return new Mirror(notifier, route, conversation.id(), prelude);
  }

  /**
   * Where the answer goes.
   *
   * <p>Nothing new is stored for this. A conversation that came from a group chat has that chat's
   * id in {@code groupId} — the surface put it there, and it is the same value the surface routes
   * by. Anything else goes to the person themselves, because a chat platform's user id addresses
   * the direct conversation between that person and the bot; so a conversation begun in the browser
   * mirrors into their own chat with the agent, which is a handoff they can pick up on a phone.
   *
   * <p>Refused across tenants. A signed-in caller's tenant is pinned by the login gate (see {@code
   * WebAuthoritiesMapper}), so this can only differ where the chat surface serves several
   * enterprises — and posting one enterprise's answer into another's chat is a leak, not a
   * mis-delivery. Refusing is the only safe direction, and it is silent to the caller because there
   * is nothing they could do about it.
   */
  private Route routeTo(final ChatSession conversation, final WebUser asker) {
    final var group = Strings.nullToEmpty(conversation.groupId()).trim();
    final var conversationTenant = Strings.nullToEmpty(conversation.tenantId()).trim();
    final var askerTenant = Strings.nullToEmpty(asker.tenantId()).trim();
    if (!conversationTenant.isEmpty()
        && !askerTenant.isEmpty()
        && !conversationTenant.equals(askerTenant)) {
      log.warn(
          "Not mirroring conversation {}: it belongs to tenant {} and the caller is in {}",
          conversation.id(),
          conversationTenant,
          askerTenant);
      return null;
    }
    final var chatId = group.isEmpty() ? Strings.nullToEmpty(asker.id()).trim() : group;
    if (chatId.isEmpty()) {
      return null;
    }
    return new Route(chatId, group.isEmpty() ? "p2p" : "group", group, askerTenant);
  }

  /**
   * The line the card opens with, above the answer.
   *
   * <p>Says who typed the message and where, because the bot is the author of the card and cannot
   * post as the person — a group reading an unattributed answer to a question it never saw asked
   * would reasonably conclude the agent had started talking to itself.
   *
   * <p>The message is quoted through {@link Notifier#quoted}, and that is load-bearing rather than
   * tidy: a chat's markdown has tags that notify people, so an unescaped message typed here would
   * let somebody ping a whole group through this bot. Every line of it is prefixed, so a message
   * with a blank line in it stays one quote block instead of half a quote and a stray paragraph.
   */
  private String prelude(
      final ChatSession conversation,
      final WebUser asker,
      final String question,
      final Notifier notifier,
      final boolean group) {
    // The reader here is not the person who typed: the card is read in a chat, whose language is
    // the deployment's rather than this request's. Asked for by name so a browser set to English
    // does not put an English line into a Chinese group's chat.
    final var locale = properties.locale() == null ? Locale.ENGLISH : properties.locale();
    final var name =
        properties.title() == null ? messages.get(locale, WebMessages.TITLE) : properties.title();
    final var link =
        properties.baseUrl().isEmpty() ? "" : properties.baseUrl() + "/#/chat/" + conversation.id();
    final var said =
        link.isEmpty()
            ? messages.get(
                locale,
                group ? "handoff-from-web-group-plain" : "handoff-from-web-plain",
                group ? new Object[] {asker.name(), name} : new Object[] {name})
            : messages.get(
                locale,
                group ? "handoff-from-web-group" : "handoff-from-web",
                group ? new Object[] {asker.name(), name, link} : new Object[] {name, link});

    final var quoted = notifier.quoted(truncated(Strings.nullToEmpty(question).strip()));
    final var out = new StringBuilder("> ").append(said).append('\n');
    for (final var line : quoted.split("\n", -1)) {
      out.append("> ").append(line).append('\n');
    }
    return out.append('\n').toString();
  }

  /**
   * A card has a size a message cannot exceed, and the answer is the part worth having. A long
   * message is quoted far enough to be recognised and no further.
   */
  private static String truncated(final String question) {
    final var limit = 400;
    return question.length() <= limit ? question : question.substring(0, limit) + "…";
  }

  /**
   * One run's mirror.
   *
   * <p>Sends once, when the run is over, rather than streaming: a chat message is not a canvas that
   * can be rewritten word by word without the surface's own card machinery, and this is the surface
   * the run is <em>not</em> happening on. What arrives is the finished answer, threaded under the
   * message the conversation started from, which is what somebody watching that thread needs.
   */
  @RequiredArgsConstructor
  private static final class Mirror implements AgentResponseListener {

    private final Notifier notifier;
    private final Route route;
    private final String inReplyTo;
    private final String prelude;

    /** {@code onContent} accumulates, so the last one seen is the whole answer. */
    private volatile String answer = "";

    @Override
    public void onContent(final String contentSoFar) {
      answer = contentSoFar;
    }

    @Override
    public void onFinished(final AgentOutcome outcome) {
      if (outcome != AgentOutcome.COMPLETED || answer.isBlank()) {
        // Nothing worth carrying. A run that failed or was stopped is reported by the surface it
        // was happening on, which is the page; repeating it in a chat would be telling a group
        // about somebody else's cancelled question.
        return;
      }
      try {
        notifier.send(route, inReplyTo, prelude + answer);
      } catch (final RuntimeException e) {
        // Swallowed, and this is the whole safety of the feature. A card can fail to send for
        // reasons that have nothing to do with the run — the bot removed from the chat, the card
        // over the platform's size limit, the platform down — and none of them is a reason for the
        // answer already on the person's screen to turn into an error.
        log.warn("Could not mirror the answer to {}", route.chatId(), e);
      }
    }
  }
}
