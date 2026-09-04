package me.kezhenxu94.springagent.integration.feishu.tools;

import com.google.common.base.Strings;
import com.lark.oapi.Client;
import com.lark.oapi.service.drive.v1.model.Member;
import com.lark.oapi.service.wiki.v2.model.ListSpaceMemberReq;
import com.lark.oapi.service.wiki.v2.model.ListSpaceMemberResp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Whether the person who asked may touch a document, spreadsheet, base, file or folder at all.
 *
 * <p>The same problem {@link FeishuChatAccess} solves, one product over. Every Feishu call this
 * application makes carries the bot's tenant token, so Feishu authorises the <em>bot</em> and
 * nothing else: any token that reaches the model — pasted into the chat, remembered from an earlier
 * turn, read off a listing — is a document the bot will happily read out or rewrite for whoever is
 * talking to it, whether or not Feishu would have shown it to them. That is wider than what the
 * person could open in their own client, and it is not a limit the model can be trusted to keep, so
 * the tools ask here first and this is the only place that decides.
 *
 * <p>Three ways in. Their own folder, which they own and which this application made for them. A
 * collaborator entry naming them. And a collaborator entry naming a <em>chat</em> they are in,
 * which is how a document shared into a group reaches everybody in that group — checked through
 * {@link FeishuChatAccess}, so there is one implementation of "is this person in that chat" rather
 * than two.
 *
 * <p><b>What it deliberately does not consult</b> is the link-sharing setting: a document set to
 * "anyone in the organisation can read" is genuinely reachable by the person, and refusing it is a
 * false negative. Honouring it would mean writing down "everybody in the tenant may use the bot's
 * authority on this", which is the blanket access this class exists to remove — and a second round
 * trip on every miss to arrive at it. A person who hits that wall can add themselves, or the chat,
 * as a collaborator.
 *
 * <p>Fails closed. A collaborator list that cannot be read leaves the answer unknown, and unknown
 * is refused.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuDriveAccess {

  /**
   * How many decisions are remembered at once. A cap rather than a size that matters: the map is
   * cleared wholesale when it is reached, since the entries are a cache of something re-derivable
   * and evicting the right one is not worth a second data structure.
   */
  static final int MAX_CACHED = 10_000;

  /**
   * How long a decision that somebody has access may be reused.
   *
   * <p>A constant rather than a knob: what it trades is one round trip per tool call against how
   * long a share that has been <em>revoked</em> keeps working, and five minutes is on the right
   * side of both. It says nothing about a share that has just been <em>granted</em> — refusals are
   * never cached, so those take effect at once.
   */
  static final Duration CACHE_TTL = Duration.ofMinutes(5);

  /**
   * The space id that means "the library of whoever is calling". Every call here is the bot's, so
   * it can only ever name the bot's own library and never a third party's.
   */
  static final String OWN_LIBRARY = "my_library";

  /** How the cache key says a wiki space, so it cannot collide with a document of the same id. */
  private static final String WIKI_SPACE = "wiki_space";

  /**
   * How many pages of a wiki space's members a check will read. At the page size below that is
   * 5,000 members - past any space a person is plausibly asked about, and the bound is here so that
   * one space id cannot turn into hundreds of requests.
   */
  static final int MAX_SPACE_MEMBER_PAGES = 50;

  static final int SPACE_MEMBER_PAGE_SIZE = 100;

  final Client feishu;
  final FeishuDriveService feishuDriveService;
  final FeishuChatAccess chatAccess;
  final FeishuUserFolders userFolders;
  final Admins admins;
  final FeishuMessages messages;

  /**
   * The decisions already made, and when they were made.
   *
   * <p>Only the allowed ones. A turn asks about the same document once per tool call and each miss
   * is one or two round trips to Feishu, so remembering a yes is what keeps a ten-call turn from
   * being a twenty-call one. A no is not remembered on purpose: refusing is cheap, and somebody who
   * has just been added as a collaborator should not have to wait out a cache to be let in.
   */
  private final Map<String, Instant> allowed = new ConcurrentHashMap<>();

  /** Raised instead of answering, so that a refusal can never be mistaken for an empty result. */
  public static class DriveAccessDeniedException extends RuntimeException {
    public DriveAccessDeniedException(final String message) {
      super(message);
    }
  }

  /**
   * @param allowed true, false, or null where the collaborator list could not be read and so says
   *     nothing either way
   */
  record Verdict(Boolean allowed, String note) {}

  /**
   * Throws unless the person this run belongs to may use {@code token}.
   *
   * @param type Feishu's name for what the token is: {@code docx}, {@code sheet}, {@code bitable},
   *     {@code file}, {@code wiki}, {@code folder}
   * @throws DriveAccessDeniedException if they may not, or if that could not be established
   */
  public void requireAccess(final ToolContext toolContext, final String token, final String type) {
    if (Strings.isNullOrEmpty(token)) {
      throw new IllegalArgumentException("token is required");
    }
    if (Strings.isNullOrEmpty(type)) {
      throw new IllegalArgumentException("type is required");
    }
    // Absent rather than empty on a run with nobody behind it, which is a run with nobody to
    // authorise: throwing here is the fail-closed answer.
    final var userId = ToolContexts.require(toolContext, ToolContexts.USER_ID);

    if (admins.isAdmin(userId)) {
      log.info(
          "Allowing admin {} onto {} {} they may not be a collaborator of", userId, type, token);
      return;
    }
    if (userFolders.isOwnFolder(toolContext, token)) {
      return;
    }
    final var key = userId + ' ' + type + ' ' + token;
    final var since = allowed.get(key);
    if (since != null && Duration.between(since, Instant.now()).compareTo(CACHE_TTL) < 0) {
      return;
    }
    allowed.remove(key);

    final var verdict = collaboration(toolContext, token, type, userId);
    if (Boolean.TRUE.equals(verdict.allowed())) {
      remember(key);
      return;
    }
    log.warn("Refused {} access to {} {}: {}", userId, type, token, verdict.note());
    throw new DriveAccessDeniedException(
        messages.get(
            verdict.allowed() == null ? "access-unknown-doc" : "access-denied-doc", type, token));
  }

  /**
   * Throws unless the person this run belongs to is a member of a wiki space.
   *
   * <p>A space is not a document: it has no collaborator list, and walking one hands back the title
   * of every node in it. Its own member list is the question instead, which is why this is a second
   * entry point rather than another {@code type} above.
   *
   * @throws DriveAccessDeniedException if they are not a member, or if that could not be
   *     established
   */
  public void requireWikiSpaceAccess(final ToolContext toolContext, final String spaceId) {
    if (Strings.isNullOrEmpty(spaceId)) {
      throw new IllegalArgumentException("spaceId is required");
    }
    final var userId = ToolContexts.require(toolContext, ToolContexts.USER_ID);
    if (admins.isAdmin(userId)) {
      log.info("Allowing admin {} into wiki space {} they may not be a member of", userId, spaceId);
      return;
    }
    // The bot's own library, whoever asks: my_library is resolved against the token making the
    // call, and every call here carries the bot's. Nobody else's library is reachable by this name.
    if (OWN_LIBRARY.equals(spaceId)) {
      return;
    }
    final var key = userId + ' ' + WIKI_SPACE + ' ' + spaceId;
    final var since = allowed.get(key);
    if (since != null && Duration.between(since, Instant.now()).compareTo(CACHE_TTL) < 0) {
      return;
    }
    allowed.remove(key);

    final var verdict = spaceMembership(spaceId, userId);
    if (Boolean.TRUE.equals(verdict.allowed())) {
      remember(key);
      return;
    }
    log.warn("Refused {} access to wiki space {}: {}", userId, spaceId, verdict.note());
    throw new DriveAccessDeniedException(
        messages.get(
            verdict.allowed() == null ? "access-unknown-wiki-space" : "access-denied-wiki-space",
            spaceId));
  }

  private Verdict spaceMembership(final String spaceId, final String userId) {
    String pageToken = null;
    for (var page = 0; page < MAX_SPACE_MEMBER_PAGES; page++) {
      final ListSpaceMemberResp resp;
      try {
        resp =
            feishu
                .wiki()
                .v2()
                .spaceMember()
                .list(
                    ListSpaceMemberReq.newBuilder()
                        .spaceId(spaceId)
                        .pageSize(SPACE_MEMBER_PAGE_SIZE)
                        .pageToken(pageToken)
                        .build());
      } catch (Exception e) {
        return new Verdict(null, "The member list could not be read: " + e.getMessage());
      }
      if (!resp.success()) {
        return new Verdict(
            null, "The member list could not be read: " + resp.getCode() + " " + resp.getMsg());
      }
      final var data = resp.getData();
      final var members =
          data == null || data.getMembers() == null
              ? new com.lark.oapi.service.wiki.v2.model.Member[0]
              : data.getMembers();
      for (final var member : members) {
        if (userId.equals(member.getMemberId())) {
          return new Verdict(true, "A member of wiki space " + spaceId + ".");
        }
      }
      if (data == null
          || !Boolean.TRUE.equals(data.getHasMore())
          || Strings.isNullOrEmpty(data.getPageToken())) {
        return new Verdict(
            false, "Not in the member list of wiki space " + spaceId + ", read in full.");
      }
      pageToken = data.getPageToken();
    }
    return new Verdict(
        null,
        "Unknown, not no: wiki space "
            + spaceId
            + " has more than "
            + MAX_SPACE_MEMBER_PAGES * SPACE_MEMBER_PAGE_SIZE
            + " members and its list was not read to the end.");
  }

  /**
   * Whether the collaborator list of {@code token} lets {@code userId} in.
   *
   * <p>Not through {@code permissions/:token/members/auth}, which looks like exactly this question
   * asked in one request. It is not: that endpoint answers for whoever the token represents, and
   * every call here carries the app's tenant token — asked on a person's behalf it would return the
   * <em>bot's</em> answer, which is yes for everything the bot can see. Answering for a person
   * would need their {@code user_access_token}, which a tool call does not have. Hence the list.
   */
  private Verdict collaboration(
      final ToolContext toolContext, final String token, final String type, final String userId) {
    final List<Member> members;
    try {
      members = feishuDriveService.listCollaborators(token, type);
    } catch (Exception e) {
      // Not rethrown: "the bot cannot see this document either" comes back this way, and the
      // caller's question — may this person use it — is answered by that, not derailed by it.
      return new Verdict(null, "The collaborator list could not be read: " + e.getMessage());
    }
    for (final var member : members) {
      if (userId.equals(member.getMemberId())) {
        return new Verdict(true, "Named on the collaborator list of " + token + ".");
      }
    }
    // Second pass rather than one: being named outright costs nothing to check, and a chat costs a
    // walk of its member list, so no chat is walked while a direct answer is still to come.
    for (final var member : members) {
      if ("openchat".equalsIgnoreCase(member.getMemberType())
          && !Strings.isNullOrEmpty(member.getMemberId())
          && chatAccess.isMemberForFiltering(toolContext, member.getMemberId())) {
        return new Verdict(
            true, "Shared with chat " + member.getMemberId() + ", which they are in.");
      }
    }
    return new Verdict(
        false,
        "Neither they nor any chat they are in is on the collaborator list of "
            + token
            + ", read in full.");
  }

  private void remember(final String key) {
    if (allowed.size() >= MAX_CACHED) {
      allowed.clear();
    }
    allowed.put(key, Instant.now());
  }
}
