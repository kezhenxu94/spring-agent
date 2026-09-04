package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.drive.v1.model.Member;
import com.lark.oapi.service.wiki.WikiService;
import com.lark.oapi.service.wiki.v2.V2;
import com.lark.oapi.service.wiki.v2.model.ListSpaceMemberReq;
import com.lark.oapi.service.wiki.v2.model.ListSpaceMemberResp;
import com.lark.oapi.service.wiki.v2.model.ListSpaceMemberRespBody;
import com.lark.oapi.service.wiki.v2.resource.SpaceMember;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The access check itself, which is the security boundary of the whole Feishu surface.
 *
 * <p>Every call this application makes carries the bot's tenant token, so a bug here does not fail
 * closed by accident — it hands whoever is talking to the bot everything the bot can see. The cases
 * below are therefore weighted towards the ways a check can wrongly say yes: an error read as
 * consent, an empty list read as "no restrictions", one person's decision reused for another, a
 * type or a token that does not match what was actually checked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeishuDriveAccessTest {

  private static final String ASKER = "ou_asker";
  private static final String STRANGER = "ou_stranger";
  private static final String ADMIN = "ou_admin";
  private static final String DOC = "doccnDOCUMENT";

  @Mock private FeishuDriveService driveService;
  @Mock private FeishuChatAccess chatAccess;
  @Mock private FeishuUserFolders userFolders;
  @Mock private Client feishu;
  @Mock private WikiService wikiService;
  @Mock private V2 wikiV2;
  @Mock private SpaceMember spaceMember;

  private FeishuDriveAccess access;

  @BeforeEach
  void setUp() {
    lenient().when(feishu.wiki()).thenReturn(wikiService);
    lenient().when(wikiService.v2()).thenReturn(wikiV2);
    lenient().when(wikiV2.spaceMember()).thenReturn(spaceMember);
    access = accessWithAdmins(Set.of(ADMIN));
  }

  private FeishuDriveAccess accessWithAdmins(final Set<String> admins) {
    return new FeishuDriveAccess(
        feishu,
        driveService,
        chatAccess,
        userFolders,
        new Admins(
            new SpringAgentProperties(
                null,
                new SpringAgentProperties.Ai(admins, Map.of(), null, null, null, null, null, null),
                Locale.ENGLISH,
                null,
                null)),
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null)));
  }

  private static ToolContext contextOf(final String userId) {
    return new ToolContext(Map.of(ToolContexts.KEY_USER_ID, userId));
  }

  private static Member user(final String openId) {
    final var member = new Member();
    member.setMemberType("openid");
    member.setMemberId(openId);
    member.setPerm("view");
    return member;
  }

  private static Member chat(final String chatId) {
    final var member = new Member();
    member.setMemberType("openchat");
    member.setMemberId(chatId);
    member.setPerm("view");
    return member;
  }

  @Nested
  @DisplayName("a document, spreadsheet, base or file")
  class Documents {

    @Test
    @DisplayName("is allowed when the person is on its collaborator list")
    void allowedWhenACollaborator() {
      when(driveService.listCollaborators(DOC, "docx")).thenReturn(List.of(user(ASKER)));

      assertThatCode(() -> access.requireAccess(contextOf(ASKER), DOC, "docx"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("is refused when somebody else is on the list but they are not")
    void refusedWhenNotACollaborator() {
      when(driveService.listCollaborators(DOC, "docx")).thenReturn(List.of(user(STRANGER)));

      assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), DOC, "docx"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class)
          .hasMessageContaining("do not have access");
    }

    @Test
    @DisplayName("is refused when the list is empty, which is not the same as unrestricted")
    void refusedWhenTheListIsEmpty() {
      when(driveService.listCollaborators(DOC, "docx")).thenReturn(List.of());

      assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), DOC, "docx"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class);
    }

    @Test
    @DisplayName("is refused when the list cannot be read at all — unknown is not yes")
    void refusedWhenTheListCannotBeRead() {
      when(driveService.listCollaborators(DOC, "docx"))
          .thenThrow(new IllegalStateException("Failed to list collaborators: no permission"));

      assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), DOC, "docx"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class)
          .hasMessageContaining("could not be established");
    }

    @Test
    @DisplayName("is allowed through a chat on the list that the person is in")
    void allowedThroughAChatTheyAreIn() {
      when(driveService.listCollaborators(DOC, "docx"))
          .thenReturn(List.of(user(STRANGER), chat("oc_team")));
      when(chatAccess.isMemberForFiltering(any(), eq("oc_team"))).thenReturn(true);

      assertThatCode(() -> access.requireAccess(contextOf(ASKER), DOC, "docx"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("is refused when the only chat on the list is one the person is not in")
    void refusedThroughAChatTheyAreNotIn() {
      when(driveService.listCollaborators(DOC, "docx")).thenReturn(List.of(chat("oc_others")));
      when(chatAccess.isMemberForFiltering(any(), eq("oc_others"))).thenReturn(false);

      assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), DOC, "docx"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class);
    }

    @Test
    @DisplayName("is refused when the only entry is a department, which cannot be resolved")
    void refusedForADepartmentEntry() {
      final var department = new Member();
      department.setMemberType("opendepartmentid");
      department.setMemberId("od_engineering");
      when(driveService.listCollaborators(DOC, "docx")).thenReturn(List.of(department));

      assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), DOC, "docx"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class);
    }

    @Test
    @DisplayName("does not walk a chat when the person is named outright, which costs nothing")
    void aNamedPersonCostsNoChatWalk() {
      when(driveService.listCollaborators(DOC, "docx"))
          .thenReturn(List.of(user(ASKER), chat("oc_team")));

      access.requireAccess(contextOf(ASKER), DOC, "docx");

      verify(chatAccess, never()).isMemberForFiltering(any(), any());
    }

    @Test
    @DisplayName(
        "is checked against the type it was given, not whichever one Feishu happens to say")
    void theTypeIsPassedThrough() {
      when(driveService.listCollaborators("shtSHEET", "sheet")).thenReturn(List.of(user(ASKER)));

      access.requireAccess(contextOf(ASKER), "shtSHEET", "sheet");

      verify(driveService).listCollaborators("shtSHEET", "sheet");
    }
  }

  @Nested
  @DisplayName("a run with nobody behind it, or an argument with nothing in it")
  class Degenerate {

    @Test
    @DisplayName("has no identity to authorise, so it is not allowed through")
    void aRunWithNoUserIsRefused() {
      assertThatThrownBy(() -> access.requireAccess(new ToolContext(Map.of()), DOC, "docx"))
          .isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(driveService);
    }

    @Test
    @DisplayName("a blank user id is not an identity either")
    void aBlankUserIsRefused() {
      assertThatThrownBy(() -> access.requireAccess(contextOf(""), DOC, "docx"))
          .isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(driveService);
    }

    @Test
    @DisplayName("a blank token is a mistake rather than a permission question")
    void aBlankTokenIsRejected() {
      assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), "", "docx"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), null, "docx"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("and so is a blank type, which would ask Feishu about nothing in particular")
    void aBlankTypeIsRejected() {
      assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), DOC, ""))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("the ways in that skip the collaborator list")
  class Bypasses {

    @Test
    @DisplayName("an admin is let in, which is what app.ai.admins already means everywhere else")
    void adminsAreLetIn() {
      access.requireAccess(contextOf(ADMIN), DOC, "docx");

      verifyNoInteractions(driveService);
    }

    @Test
    @DisplayName("somebody who is not an admin is not, even with the same document")
    void nonAdminsAreNot() {
      when(driveService.listCollaborators(DOC, "docx")).thenReturn(List.of());

      assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), DOC, "docx"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class);
    }

    @Test
    @DisplayName("their own folder needs no list, since this application made it for them")
    void theirOwnFolderIsAllowed() {
      when(userFolders.isOwnFolder(any(), eq("fldMINE"))).thenReturn(true);

      access.requireAccess(contextOf(ASKER), "fldMINE", "folder");

      verifyNoInteractions(driveService);
    }

    @Test
    @DisplayName("somebody else's folder still goes through the list")
    void anotherPersonsFolderDoesNot() {
      when(userFolders.isOwnFolder(any(), eq("fldTHEIRS"))).thenReturn(false);
      when(driveService.listCollaborators("fldTHEIRS", "folder")).thenReturn(List.of());

      assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), "fldTHEIRS", "folder"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class);
      verify(driveService).listCollaborators("fldTHEIRS", "folder");
    }
  }

  @Nested
  @DisplayName("remembering a decision")
  class Caching {

    @Test
    @DisplayName("asks Feishu once for the same person, document and type")
    void anAllowedDecisionIsReused() {
      when(driveService.listCollaborators(DOC, "docx")).thenReturn(List.of(user(ASKER)));

      access.requireAccess(contextOf(ASKER), DOC, "docx");
      access.requireAccess(contextOf(ASKER), DOC, "docx");

      verify(driveService, times(1)).listCollaborators(DOC, "docx");
    }

    @Test
    @DisplayName("never lets one person in on another person's yes")
    void aDecisionIsNotSharedBetweenPeople() {
      when(driveService.listCollaborators(DOC, "docx"))
          .thenReturn(List.of(user(ASKER)))
          .thenReturn(List.of(user(ASKER)));

      access.requireAccess(contextOf(ASKER), DOC, "docx");

      assertThatThrownBy(() -> access.requireAccess(contextOf(STRANGER), DOC, "docx"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class);
    }

    @Test
    @DisplayName("does not carry a yes about one document over to another")
    void aDecisionIsNotSharedBetweenDocuments() {
      when(driveService.listCollaborators(DOC, "docx")).thenReturn(List.of(user(ASKER)));
      when(driveService.listCollaborators("doccnOTHER", "docx")).thenReturn(List.of());

      access.requireAccess(contextOf(ASKER), DOC, "docx");

      assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), "doccnOTHER", "docx"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class);
    }

    @Test
    @DisplayName("does not carry a yes about one type over to another of the same token")
    void aDecisionIsNotSharedBetweenTypes() {
      when(driveService.listCollaborators(DOC, "docx")).thenReturn(List.of(user(ASKER)));
      when(driveService.listCollaborators(DOC, "sheet")).thenReturn(List.of());

      access.requireAccess(contextOf(ASKER), DOC, "docx");

      assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), DOC, "sheet"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class);
    }

    @Test
    @DisplayName("does not remember a refusal, so a share granted just now takes effect at once")
    void aRefusalIsNotRemembered() {
      when(driveService.listCollaborators(DOC, "docx"))
          .thenReturn(List.of())
          .thenReturn(List.of(user(ASKER)));

      assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), DOC, "docx"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class);
      assertThatCode(() -> access.requireAccess(contextOf(ASKER), DOC, "docx"))
          .doesNotThrowAnyException();

      verify(driveService, times(2)).listCollaborators(DOC, "docx");
    }
  }

  @Nested
  @DisplayName("a wiki space, which has members rather than collaborators")
  class WikiSpaces {

    private ListSpaceMemberResp page(final boolean hasMore, final String next, final String... ids)
        throws Exception {
      final var body = new ListSpaceMemberRespBody();
      final var members = new com.lark.oapi.service.wiki.v2.model.Member[ids.length];
      for (var i = 0; i < ids.length; i++) {
        final var member = new com.lark.oapi.service.wiki.v2.model.Member();
        member.setMemberId(ids[i]);
        member.setMemberType("openid");
        members[i] = member;
      }
      body.setMembers(members);
      body.setHasMore(hasMore);
      body.setPageToken(next);
      final var resp = new ListSpaceMemberResp();
      resp.setCode(0);
      resp.setData(body);
      return resp;
    }

    @Test
    @DisplayName("is allowed when the person is in its member list")
    void allowedWhenAMember() throws Exception {
      when(spaceMember.list(any(ListSpaceMemberReq.class)))
          .thenReturn(page(false, null, STRANGER, ASKER));

      assertThatCode(() -> access.requireWikiSpaceAccess(contextOf(ASKER), "7100"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("is refused when they are not, having read the list to the end")
    void refusedWhenNotAMember() throws Exception {
      when(spaceMember.list(any(ListSpaceMemberReq.class))).thenReturn(page(false, null, STRANGER));

      assertThatThrownBy(() -> access.requireWikiSpaceAccess(contextOf(ASKER), "7100"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class)
          .hasMessageContaining("not a member");
    }

    @Test
    @DisplayName("keeps reading pages until it finds them")
    void walksEveryPage() throws Exception {
      when(spaceMember.list(any(ListSpaceMemberReq.class)))
          .thenReturn(page(true, "cursor", STRANGER))
          .thenReturn(page(false, null, ASKER));

      assertThatCode(() -> access.requireWikiSpaceAccess(contextOf(ASKER), "7100"))
          .doesNotThrowAnyException();
      verify(spaceMember, times(2)).list(any(ListSpaceMemberReq.class));
    }

    @Test
    @DisplayName("is refused when the member list errors — unknown is not yes here either")
    void refusedWhenTheListErrors() throws Exception {
      final var failure = new ListSpaceMemberResp();
      failure.setCode(131006);
      failure.setMsg("permission denied");
      when(spaceMember.list(any(ListSpaceMemberReq.class))).thenReturn(failure);

      assertThatThrownBy(() -> access.requireWikiSpaceAccess(contextOf(ASKER), "7100"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class)
          .hasMessageContaining("could not be established");
    }

    @Test
    @DisplayName("is refused when the call itself throws")
    void refusedWhenTheCallThrows() throws Exception {
      when(spaceMember.list(any(ListSpaceMemberReq.class))).thenThrow(new RuntimeException("boom"));

      assertThatThrownBy(() -> access.requireWikiSpaceAccess(contextOf(ASKER), "7100"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class);
    }

    @Test
    @DisplayName("my_library is the bot's own and needs no list")
    void theBotsOwnLibraryNeedsNoCheck() {
      assertThatCode(
              () -> access.requireWikiSpaceAccess(contextOf(ASKER), FeishuDriveAccess.OWN_LIBRARY))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an admin is let in, as everywhere else")
    void adminsAreLetIn() {
      assertThatCode(() -> access.requireWikiSpaceAccess(contextOf(ADMIN), "7100"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a run with nobody behind it has nobody to be a member")
    void aRunWithNoUserIsRefused() {
      assertThatThrownBy(() -> access.requireWikiSpaceAccess(new ToolContext(Map.of()), "7100"))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a wiki space id cannot be let in by a document decision of the same id")
    void aSpaceIsNotADocument() throws Exception {
      when(driveService.listCollaborators("7100", "docx")).thenReturn(List.of(user(ASKER)));
      when(spaceMember.list(any(ListSpaceMemberReq.class))).thenReturn(page(false, null, STRANGER));

      access.requireAccess(contextOf(ASKER), "7100", "docx");

      assertThatThrownBy(() -> access.requireWikiSpaceAccess(contextOf(ASKER), "7100"))
          .isInstanceOf(FeishuDriveAccess.DriveAccessDeniedException.class);
    }
  }

  @Test
  @DisplayName("a refusal is worded for the model, in the workspace's language")
  void refusalsAreLocalised() {
    when(driveService.listCollaborators(DOC, "docx")).thenReturn(List.of());

    assertThatThrownBy(() -> access.requireAccess(contextOf(ASKER), DOC, "docx"))
        .hasMessageContaining(DOC)
        .hasMessageContaining("docx");
    assertThat(FeishuDriveAccess.CACHE_TTL.toMinutes()).isEqualTo(5);
  }
}
