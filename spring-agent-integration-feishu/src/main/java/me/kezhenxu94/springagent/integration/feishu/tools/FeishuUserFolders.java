package me.kezhenxu94.springagent.integration.feishu.tools;

import com.google.common.base.Strings;
import com.lark.oapi.service.drive.v1.model.BaseMember;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * The drive folder that belongs to the person a run belongs to.
 *
 * <p>Everything the agent creates in Feishu used to land in one folder named by a constant, owned
 * by the bot and shared by everybody who had ever talked to it. There was no boundary to manage:
 * one person's documents sat beside another's, and the only thing keeping them apart was that
 * nobody had been given the folder's link. This gives each person a folder of their own, inside the
 * bot's space, and hands them its ownership.
 *
 * <p><b>The folder is named by open_id</b>, which is not the friendliest thing to read in a drive
 * and is deliberate anyway. No display name reaches a tool — a run carries the sender's open_id and
 * nothing else, and looking one up would mean a contact scope this application does not ask for —
 * and a name is neither unique between two people nor stable across one person renaming themselves.
 * An open_id is both, which is what lets the folder be found again by listing rather than by
 * remembering: nothing about this is written down on our side.
 *
 * <p>Ownership goes to the person, but the folder stays where it is (see {@link
 * FeishuDriveService#transferOwner}). Both halves matter: they own what is theirs and it counts
 * against their space rather than the bot's, while the bot keeps {@code full_access} and keeps
 * finding the folder in its own listing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuUserFolders {

  /** Feishu's own name for a folder, in both the listing and the permission APIs. */
  static final String FOLDER_TYPE = "folder";

  final FeishuDriveService feishuDriveService;
  final FeishuPermissionTools feishuPermissionTools;

  /**
   * open_id to folder token, for the life of the process.
   *
   * <p>Resolution runs under {@link ConcurrentHashMap#computeIfAbsent}, which is what stops two
   * runs for the same person from both creating a folder. It holds the map's bin for the duration
   * of a call to Feishu, which is only tolerable because the key is one person and the call happens
   * once for them: everybody else's lookups are unaffected.
   */
  private final Map<String, String> foldersByUser = new ConcurrentHashMap<>();

  /**
   * The folder this run's files belong in, created and handed over the first time somebody asks.
   *
   * @throws IllegalStateException on a run with nobody behind it, which is a run with nobody to own
   *     a folder — the same fail-closed answer {@code FeishuChatAccess} gives
   */
  public String folderFor(final ToolContext toolContext) {
    final var userId = ToolContexts.require(toolContext, ToolContexts.USER_ID);
    return foldersByUser.computeIfAbsent(userId, this::resolve);
  }

  /** Whether {@code token} is this person's own folder, without creating one if it is not. */
  public boolean isOwnFolder(final ToolContext toolContext, final String token) {
    if (Strings.isNullOrEmpty(token)) {
      return false;
    }
    final var userId = ToolContexts.get(toolContext, ToolContexts.USER_ID);
    return !Strings.isNullOrEmpty(userId) && token.equals(foldersByUser.get(userId));
  }

  /**
   * Finds this person's folder in the bot's space, or makes one.
   *
   * <p>Found by listing rather than remembered, so a restart, a second replica and a fresh
   * deployment all arrive at the same folder. Two processes can still both look and both create;
   * that is why a duplicate is resolved by taking the earliest, which is an answer every process
   * agrees on rather than whichever one it happened to see first.
   */
  private String resolve(final String userId) {
    final var root = feishuDriveService.rootFolderToken();
    final var existing =
        feishuDriveService.listFolderFiles(root).stream()
            .filter(file -> FOLDER_TYPE.equals(file.getType()) && userId.equals(file.getName()))
            .min(Comparator.comparing(file -> Strings.nullToEmpty(file.getCreatedTime())))
            .orElse(null);
    if (existing != null) {
      log.info("Using the existing drive folder {} for {}", existing.getToken(), userId);
      return existing.getToken();
    }

    final var token = feishuDriveService.createFolder(root, userId);
    // Granted before the handover rather than left to it: transferOwner needs the new owner to be a
    // collaborator already, and a failure here is worth having before ownership has moved.
    feishuPermissionTools.grant(
        token,
        FOLDER_TYPE,
        BaseMember.newBuilder()
            .memberType("openid")
            .memberId(userId)
            .perm("full_access")
            .type("user")
            .build());
    feishuDriveService.transferOwner(token, FOLDER_TYPE, userId);
    log.info("Created the drive folder {} for {}", token, userId);
    return token;
  }
}
