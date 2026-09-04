package me.kezhenxu94.springagent.integration.feishu.tools;

import com.lark.oapi.Client;
import com.lark.oapi.service.wiki.v2.model.GetNodeSpaceReq;
import com.lark.oapi.service.wiki.v2.model.ListSpaceNodeReq;
import com.lark.oapi.service.wiki.v2.model.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class FeishuWikiTools {

  /**
   * The largest page the endpoint accepts. Checked here rather than left to Feishu because the
   * error it answers with names neither the limit nor the argument that broke it, so a model cannot
   * tell from the failure that it should ask for fewer.
   */
  private static final int MAX_NODES_PER_PAGE = 50;

  /**
   * How many nodes one walk may return before it stops and says so. A wiki space is unbounded and a
   * deep walk of a large one would fill the context with titles the model never needed; capping it
   * and reporting the truncation lets the model narrow the subtree instead of silently believing it
   * has seen the whole space.
   */
  private static final int MAX_NODES_PER_WALK = 500;

  final Client feishu;

  @Builder
  @Jacksonized
  public static record WikiNodeInfo(
      String spaceId,
      String nodeToken,
      String objToken,
      String objType,
      String parentNodeToken,
      String nodeType,
      String originNodeToken,
      String originSpaceId,
      boolean hasChild,
      String title,
      String objCreateTime,
      String objEditTime,
      String nodeCreateTime,
      String creator,
      String owner,
      String nodeCreator) {}

  @Tool(
      name = "FeishuGetWikiNodeInfo",
      description =
          "Look up a Feishu wiki node: its title, node type, the id of the wiki space it belongs"
              + " to, and the token (objToken) and type (objType) of the cloud document behind it."
              + " Takes a wiki node link (https://xxx.feishu.cn/wiki/xxxxx), a document link"
              + " (.../docx/xxx, .../sheets/xxx), or a bare token.\n"
              + "**When objType comes back as sheet the node is a spreadsheet: pass the objToken as"
              + " spreadsheetToken to the sheet tools (FeishuListSheets, FeishuSheetReadRange,"
              + " FeishuSheetUpdateRange and the rest) to read or change it.** When it is docx or"
              + " doc, pass the objToken as documentId to the doc tools:"
              + " FeishuGetDocumentRawContent for the plain text, FeishuListDocBlocks and"
              + " FeishuGetDocBlockChildren for the block structure. When it is bitable, pass the"
              + " objToken as appToken to the bitable tools: FeishuListBitableTables for what it"
              + " holds and FeishuSearchBitableRecords for the rows of one of its tables.")
  @SneakyThrows
  public WikiNodeInfo getWikiNodeInfo(
      @ToolParam(
              description = "A wiki node link, a document link, or a bare node or document token")
          String urlOrToken,
      @ToolParam(
              description =
                  "Document type, required when urlOrToken is a document token rather than a wiki"
                      + " node token: one of doc, docx, sheet, mindnote, bitable, file, slides,"
                      + " wiki. Leave it out for a link or a wiki node token, which are recognised"
                      + " on their own and otherwise treated as wiki",
              required = false)
          String objType) {

    final var resolved = resolveTokenAndObjType(urlOrToken, objType);

    final var reqBuilder = GetNodeSpaceReq.newBuilder().token(resolved.token());
    if (resolved.objType() != null && !resolved.objType().isBlank()) {
      reqBuilder.objType(resolved.objType());
    }

    final var resp = feishu.wiki().v2().space().getNode(reqBuilder.build());
    if (!resp.success()) {
      log.error(
          "Failed to get wiki node info for '{}': {}, {}",
          urlOrToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to get wiki node info: " + resp.getMsg());
    }

    final var node = resp.getData().getNode();
    log.info("Read wiki node info: token={}, objType={}", node.getNodeToken(), node.getObjType());
    return WikiNodeInfo.builder()
        .spaceId(node.getSpaceId())
        .nodeToken(node.getNodeToken())
        .objToken(node.getObjToken())
        .objType(node.getObjType())
        .parentNodeToken(node.getParentNodeToken())
        .nodeType(node.getNodeType())
        .originNodeToken(node.getOriginNodeToken())
        .originSpaceId(node.getOriginSpaceId())
        .hasChild(Boolean.TRUE.equals(node.getHasChild()))
        .title(node.getTitle())
        .objCreateTime(node.getObjCreateTime())
        .objEditTime(node.getObjEditTime())
        .nodeCreateTime(node.getNodeCreateTime())
        .creator(node.getCreator())
        .owner(node.getOwner())
        .nodeCreator(node.getNodeCreator())
        .build();
  }

  @Builder
  @Jacksonized
  public static record WikiNodeList(
      List<WikiNodeInfo> nodes, String pageToken, boolean hasMore, boolean truncated) {}

  @Tool(
      name = "FeishuListWikiNodes",
      description =
          "The child nodes of a wiki space or of one wiki node: their titles, node tokens, and the"
              + " token (objToken) and type (objType) of the document behind each. This is how a"
              + " wiki gets walked — the space's top level first, then the nodes under a node whose"
              + " hasChild is true — and how a whole space's documents get found before reading"
              + " them with the doc, sheet or bitable tools.\n"
              + "Set maxDepth above 1 to walk deeper in one call instead of one call per node: the"
              + " nodes come back flat, each carrying the parentNodeToken it hangs from, so the"
              + " tree can be rebuilt from the list. A walk stops after "
              + MAX_NODES_PER_WALK
              + " nodes and answers truncated=true; when that happens, walk a smaller subtree by"
              + " passing its node as parentNodeToken rather than asking for the same depth again."
              + " pageToken and hasMore only ever describe the level named by parentNodeToken (the"
              + " space's top level when it is left out); deeper levels are paged through in full"
              + " on their own.")
  @SneakyThrows
  public WikiNodeList listWikiNodes(
      @ToolParam(
              description =
                  "The wiki space id, as FeishuGetWikiNodeInfo reports it (spaceId). Pass"
                      + " my_library for the current user's own document library")
          String spaceId,
      @ToolParam(
              description =
                  "The node whose children are wanted; left out for the space's top level",
              required = false)
          String parentNodeToken,
      @ToolParam(
              description = "page_token of a previous page's result, to read the next one",
              required = false)
          String pageToken,
      @ToolParam(
              description = "Nodes per page, 50 at most; 50 by default to keep a walk short",
              required = false)
          Integer pageSize,
      @ToolParam(
              description =
                  "How many levels to walk, 1 by default meaning the direct children only. A"
                      + " negative value walks the whole subtree, bounded by the node cap",
              required = false)
          Integer maxDepth) {

    if (pageSize != null && (pageSize < 1 || pageSize > MAX_NODES_PER_PAGE)) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and " + MAX_NODES_PER_PAGE + ", was " + pageSize);
    }

    return listWikiNodes(
        spaceId,
        parentNodeToken,
        pageToken,
        maxDepth == null ? 1 : maxDepth,
        (space, parent, cursor) -> listOnePage(space, parent, cursor, pageSize));
  }

  /**
   * Where a page of nodes comes from. Split out from {@link #listWikiNodes} so that the walk — the
   * part with the depth bookkeeping and the node cap — can be tested without an API to answer it.
   */
  interface NodePageFetcher {
    NodePage fetch(String spaceId, String parentNodeToken, String pageToken);
  }

  WikiNodeList listWikiNodes(
      final String spaceId,
      final String parentNodeToken,
      final String pageToken,
      final int maxDepth,
      final NodePageFetcher fetcher) {

    if (spaceId == null || spaceId.isBlank()) {
      throw new IllegalArgumentException("spaceId is required");
    }

    final var nodes = new ArrayList<WikiNodeInfo>();
    final var firstPage = fetcher.fetch(spaceId, parentNodeToken, pageToken);
    nodes.addAll(firstPage.nodes());

    final var pending = new ArrayDeque<PendingLevel>();
    if (maxDepth != 1) {
      // The children of the level asked for sit one level deeper, hence 2.
      firstPage.nodes().forEach(node -> queueChildrenOf(node, 2, spaceId, maxDepth, pending));

      while (!pending.isEmpty() && nodes.size() < MAX_NODES_PER_WALK) {
        final var level = pending.poll();
        String cursor = null;
        do {
          final var page = fetcher.fetch(level.spaceId(), level.nodeToken(), cursor);
          for (final var node : page.nodes()) {
            if (nodes.size() >= MAX_NODES_PER_WALK) {
              break;
            }
            nodes.add(node);
            queueChildrenOf(node, level.depth() + 1, level.spaceId(), maxDepth, pending);
          }
          cursor = page.hasMore() ? page.pageToken() : null;
        } while (cursor != null && nodes.size() < MAX_NODES_PER_WALK);
      }
    }

    final var truncated = !pending.isEmpty();
    if (truncated) {
      log.info(
          "Walk of wiki space {} under '{}' stopped at the {} node cap",
          spaceId,
          parentNodeToken,
          MAX_NODES_PER_WALK);
    } else {
      log.info(
          "Listed {} wiki nodes of space {} under '{}', depth {}",
          nodes.size(),
          spaceId,
          parentNodeToken,
          maxDepth);
    }
    return WikiNodeList.builder()
        .nodes(nodes)
        .pageToken(firstPage.pageToken())
        .hasMore(firstPage.hasMore())
        .truncated(truncated)
        .build();
  }

  /**
   * A node's children are only worth a request when it says it has some, and only while the depth
   * they sit at is still within the walk. A shortcut's children belong to the space the shortcut
   * points at, not to the one being walked, so it is descended into through its origin node and
   * origin space — asking the walked space for them answers "node not found".
   */
  private void queueChildrenOf(
      final WikiNodeInfo node,
      final int depth,
      final String spaceId,
      final int maxDepth,
      final ArrayDeque<PendingLevel> pending) {
    if (!node.hasChild() || (maxDepth > 0 && depth > maxDepth)) {
      return;
    }
    final var shortcut = node.originNodeToken() != null && !node.originNodeToken().isBlank();
    pending.add(
        new PendingLevel(
            shortcut ? node.originNodeToken() : node.nodeToken(),
            shortcut && node.originSpaceId() != null && !node.originSpaceId().isBlank()
                ? node.originSpaceId()
                : spaceId,
            depth));
  }

  record PendingLevel(String nodeToken, String spaceId, int depth) {}

  record NodePage(List<WikiNodeInfo> nodes, String pageToken, boolean hasMore) {}

  @SneakyThrows
  private NodePage listOnePage(
      final String spaceId,
      final String parentNodeToken,
      final String pageToken,
      final Integer pageSize) {

    final var reqBuilder = ListSpaceNodeReq.newBuilder().spaceId(spaceId);
    if (parentNodeToken != null && !parentNodeToken.isBlank()) {
      reqBuilder.parentNodeToken(parentNodeToken);
    }
    if (pageToken != null && !pageToken.isBlank()) {
      reqBuilder.pageToken(pageToken);
    }
    reqBuilder.pageSize(pageSize == null ? MAX_NODES_PER_PAGE : pageSize);

    final var resp = feishu.wiki().v2().spaceNode().list(reqBuilder.build());
    if (!resp.success()) {
      log.error(
          "Failed to list wiki nodes of space {} under '{}': {}, {}",
          spaceId,
          parentNodeToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to list wiki nodes: " + resp.getMsg());
    }

    final var items = resp.getData().getItems();
    final var nodes = new ArrayList<WikiNodeInfo>();
    if (items != null) {
      for (final var item : items) {
        nodes.add(toWikiNodeInfo(item));
      }
    }
    return new NodePage(
        nodes, resp.getData().getPageToken(), Boolean.TRUE.equals(resp.getData().getHasMore()));
  }

  private static WikiNodeInfo toWikiNodeInfo(final Node node) {
    return WikiNodeInfo.builder()
        .spaceId(node.getSpaceId())
        .nodeToken(node.getNodeToken())
        .objToken(node.getObjToken())
        .objType(node.getObjType())
        .parentNodeToken(node.getParentNodeToken())
        .nodeType(node.getNodeType())
        .originNodeToken(node.getOriginNodeToken())
        .originSpaceId(node.getOriginSpaceId())
        .hasChild(Boolean.TRUE.equals(node.getHasChild()))
        .title(node.getTitle())
        .objCreateTime(node.getObjCreateTime())
        .objEditTime(node.getObjEditTime())
        .nodeCreateTime(node.getNodeCreateTime())
        .creator(node.getCreator())
        .owner(node.getOwner())
        .nodeCreator(node.getNodeCreator())
        .build();
  }

  record ResolvedToken(String token, String objType) {}

  /**
   * The token and type an argument names.
   *
   * <p>The resolution itself lives in {@link FeishuGuardedTools}, which is also what {@link
   * FeishuAccessInterceptor} checks this call against. One implementation on purpose: two would let
   * the guard rule on one document while this opened another, which is the only way past the check
   * that would not show up as an error.
   */
  ResolvedToken resolveTokenAndObjType(final String urlOrToken, final String objType) {
    if (urlOrToken == null || urlOrToken.isBlank()) {
      throw new IllegalArgumentException("urlOrToken is required");
    }
    final var resolved =
        FeishuGuardedTools.resolve(
            urlOrToken, objType == null || objType.isBlank() ? null : objType);
    return new ResolvedToken(resolved.token(), resolved.type());
  }
}
