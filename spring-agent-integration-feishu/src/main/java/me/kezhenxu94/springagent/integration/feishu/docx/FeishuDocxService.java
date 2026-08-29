package me.kezhenxu94.springagent.integration.feishu.docx;

import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.service.docx.v1.model.BatchDeleteDocumentBlockChildrenReq;
import com.lark.oapi.service.docx.v1.model.BatchDeleteDocumentBlockChildrenReqBody;
import com.lark.oapi.service.docx.v1.model.BatchUpdateDocumentBlockReq;
import com.lark.oapi.service.docx.v1.model.BatchUpdateDocumentBlockReqBody;
import com.lark.oapi.service.docx.v1.model.Block;
import com.lark.oapi.service.docx.v1.model.ConvertDocumentReq;
import com.lark.oapi.service.docx.v1.model.ConvertDocumentReqBody;
import com.lark.oapi.service.docx.v1.model.ConvertDocumentRespBody;
import com.lark.oapi.service.docx.v1.model.CreateDocumentBlockChildrenReq;
import com.lark.oapi.service.docx.v1.model.CreateDocumentBlockChildrenReqBody;
import com.lark.oapi.service.docx.v1.model.CreateDocumentBlockDescendantReq;
import com.lark.oapi.service.docx.v1.model.CreateDocumentBlockDescendantReqBody;
import com.lark.oapi.service.docx.v1.model.CreateDocumentBlockDescendantRespBody;
import com.lark.oapi.service.docx.v1.model.CreateDocumentReq;
import com.lark.oapi.service.docx.v1.model.CreateDocumentReqBody;
import com.lark.oapi.service.docx.v1.model.Document;
import com.lark.oapi.service.docx.v1.model.GetDocumentBlockChildrenReq;
import com.lark.oapi.service.docx.v1.model.GetDocumentBlockReq;
import com.lark.oapi.service.docx.v1.model.GetDocumentReq;
import com.lark.oapi.service.docx.v1.model.ListDocumentBlockReq;
import com.lark.oapi.service.docx.v1.model.PatchDocumentBlockReq;
import com.lark.oapi.service.docx.v1.model.RawContentDocumentReq;
import com.lark.oapi.service.docx.v1.model.UpdateBlockRequest;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuDocxService {

  private final Client feishu;

  @SneakyThrows
  public Document createDocument(final String folderToken, final String title) {
    final var resp =
        feishu
            .docx()
            .v1()
            .document()
            .create(
                CreateDocumentReq.newBuilder()
                    .createDocumentReqBody(
                        CreateDocumentReqBody.newBuilder()
                            .folderToken(folderToken)
                            .title(title)
                            .build())
                    .build());
    if (!resp.success()) {
      log.error("Failed to create document '{}': {}, {}", title, resp.getCode(), resp.getMsg());
      throw new IllegalStateException("Failed to create document: " + resp.getMsg());
    }
    final var document = resp.getData().getDocument();
    log.info("Created document '{}': documentId={}", title, document.getDocumentId());
    return document;
  }

  @SneakyThrows
  public Document getDocumentInfo(final String documentId) {
    final var resp =
        feishu
            .docx()
            .v1()
            .document()
            .get(GetDocumentReq.newBuilder().documentId(documentId).build());
    if (!resp.success()) {
      log.error(
          "Failed to get info of document {}: {}, {}", documentId, resp.getCode(), resp.getMsg());
      throw new IllegalStateException("Failed to get document info: " + resp.getMsg());
    }
    return resp.getData().getDocument();
  }

  @SneakyThrows
  public String getDocumentRawContent(final String documentId) {
    final var resp =
        feishu
            .docx()
            .v1()
            .document()
            .rawContent(RawContentDocumentReq.newBuilder().documentId(documentId).build());
    if (!resp.success()) {
      log.error(
          "Failed to get raw content of document {}: {}, {}",
          documentId,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to get document raw content: " + resp.getMsg());
    }
    log.info("Read raw content of document {}", documentId);
    return resp.getData().getContent();
  }

  @SneakyThrows
  public String listDocumentBlocks(
      final String documentId,
      final Integer documentRevisionId,
      final String pageToken,
      final Integer pageSize) {
    final var resp =
        feishu
            .docx()
            .v1()
            .documentBlock()
            .list(
                ListDocumentBlockReq.newBuilder()
                    .documentId(documentId)
                    .documentRevisionId(documentRevisionId)
                    .pageToken(pageToken)
                    .pageSize(pageSize)
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to list blocks of document {}: {}, {}",
          documentId,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to list document blocks: " + resp.getMsg());
    }
    log.info("Listed blocks of document {}", documentId);
    return Jsons.DEFAULT.toJson(resp.getData());
  }

  @SneakyThrows
  public String getDocumentBlockChildren(
      final String documentId,
      final String blockId,
      final Boolean withDescendants,
      final Integer documentRevisionId,
      final String pageToken,
      final Integer pageSize) {
    final var resp =
        feishu
            .docx()
            .v1()
            .documentBlockChildren()
            .get(
                GetDocumentBlockChildrenReq.newBuilder()
                    .documentId(documentId)
                    .blockId(blockId)
                    .withDescendants(withDescendants)
                    .documentRevisionId(documentRevisionId)
                    .pageToken(pageToken)
                    .pageSize(pageSize)
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to get children of block {} in document {}: {}, {}",
          blockId,
          documentId,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to get document block children: " + resp.getMsg());
    }
    log.info("Got children of block {} in document {}", blockId, documentId);
    return Jsons.DEFAULT.toJson(resp.getData());
  }

  @SneakyThrows
  public String getDocumentBlock(
      final String documentId, final String blockId, final Integer documentRevisionId) {
    final var resp =
        feishu
            .docx()
            .v1()
            .documentBlock()
            .get(
                GetDocumentBlockReq.newBuilder()
                    .documentId(documentId)
                    .blockId(blockId)
                    .documentRevisionId(documentRevisionId)
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to get block {} in document {}: {}, {}",
          blockId,
          documentId,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to get document block: " + resp.getMsg());
    }
    return Jsons.DEFAULT.toJson(resp.getData().getBlock());
  }

  @SneakyThrows
  public String createDocumentBlockChildren(
      final String documentId,
      final String blockId,
      final String childrenJson,
      final Integer index,
      final Integer documentRevisionId,
      final String clientToken) {
    final var children = Jsons.DEFAULT.fromJson(childrenJson, Block[].class);
    if (children == null) {
      throw new IllegalArgumentException("childrenJson must be a valid non-null JSON array");
    }
    final var resp =
        feishu
            .docx()
            .v1()
            .documentBlockChildren()
            .create(
                CreateDocumentBlockChildrenReq.newBuilder()
                    .documentId(documentId)
                    .blockId(blockId)
                    .documentRevisionId(documentRevisionId)
                    .clientToken(clientToken)
                    .createDocumentBlockChildrenReqBody(
                        CreateDocumentBlockChildrenReqBody.newBuilder()
                            .children(children)
                            .index(index)
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to create children under block {} in document {}: {}, {}",
          blockId,
          documentId,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to create document block children: " + resp.getMsg());
    }
    log.info(
        "Created {} child block(s) under block {} in document {}",
        children.length,
        blockId,
        documentId);
    return Jsons.DEFAULT.toJson(resp.getData());
  }

  @SneakyThrows
  public String createDocumentBlockDescendant(
      final String documentId,
      final String blockId,
      final String[] childrenId,
      final String descendantsJson,
      final Integer index,
      final Integer documentRevisionId,
      final String clientToken) {
    final var descendants = Jsons.DEFAULT.fromJson(descendantsJson, Block[].class);
    if (descendants == null) {
      throw new IllegalArgumentException("descendantsJson must be a valid non-null JSON array");
    }
    return Jsons.DEFAULT.toJson(
        createDescendants(
            documentId, blockId, childrenId, descendants, index, documentRevisionId, clientToken));
  }

  /** The same insert, taking and returning what the SDK models rather than JSON on both sides. */
  @SneakyThrows
  public CreateDocumentBlockDescendantRespBody createDescendants(
      final String documentId,
      final String blockId,
      final String[] childrenId,
      final Block[] descendants,
      final Integer index,
      final Integer documentRevisionId,
      final String clientToken) {
    final var resp =
        feishu
            .docx()
            .v1()
            .documentBlockDescendant()
            .create(
                CreateDocumentBlockDescendantReq.newBuilder()
                    .documentId(documentId)
                    .blockId(blockId)
                    .documentRevisionId(documentRevisionId)
                    .clientToken(clientToken)
                    .createDocumentBlockDescendantReqBody(
                        CreateDocumentBlockDescendantReqBody.newBuilder()
                            .childrenId(childrenId)
                            .index(index)
                            .descendants(descendants)
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to create descendant blocks under block {} in document {}: {}, {}",
          blockId,
          documentId,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException(
          "Failed to create document block descendants: " + resp.getMsg());
    }
    log.info(
        "Created {} descendant block(s) under block {} in document {}",
        descendants.length,
        blockId,
        documentId);
    return resp.getData();
  }

  @SneakyThrows
  public String patchDocumentBlock(
      final String documentId,
      final String blockId,
      final String updateOperationJson,
      final Integer documentRevisionId,
      final String clientToken) {
    final var updateBlockRequest =
        Jsons.DEFAULT.fromJson(updateOperationJson, UpdateBlockRequest.class);
    if (updateBlockRequest == null) {
      throw new IllegalArgumentException(
          "updateOperationJson must be a valid non-null JSON object");
    }
    final var resp =
        feishu
            .docx()
            .v1()
            .documentBlock()
            .patch(
                PatchDocumentBlockReq.newBuilder()
                    .documentId(documentId)
                    .blockId(blockId)
                    .documentRevisionId(documentRevisionId)
                    .clientToken(clientToken)
                    .updateBlockRequest(updateBlockRequest)
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to patch block {} in document {}: {}, {}",
          blockId,
          documentId,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to patch document block: " + resp.getMsg());
    }
    log.info("Patched block {} in document {}", blockId, documentId);
    return Jsons.DEFAULT.toJson(resp.getData());
  }

  @SneakyThrows
  public String batchUpdateDocumentBlocks(
      final String documentId,
      final String requestsJson,
      final Integer documentRevisionId,
      final String clientToken) {
    final var requests = Jsons.DEFAULT.fromJson(requestsJson, UpdateBlockRequest[].class);
    if (requests == null) {
      throw new IllegalArgumentException("requestsJson must be a valid non-null JSON array");
    }
    final var resp =
        feishu
            .docx()
            .v1()
            .documentBlock()
            .batchUpdate(
                BatchUpdateDocumentBlockReq.newBuilder()
                    .documentId(documentId)
                    .documentRevisionId(documentRevisionId)
                    .clientToken(clientToken)
                    .batchUpdateDocumentBlockReqBody(
                        BatchUpdateDocumentBlockReqBody.newBuilder().requests(requests).build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to batch update {} block(s) in document {}: {}, {}",
          requests.length,
          documentId,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to batch update document blocks: " + resp.getMsg());
    }
    log.info("Batch updated {} block(s) in document {}", requests.length, documentId);
    return Jsons.DEFAULT.toJson(resp.getData());
  }

  @SneakyThrows
  public void deleteDocumentBlockChildren(
      final String documentId,
      final String blockId,
      final int startIndex,
      final int endIndex,
      final Integer documentRevisionId,
      final String clientToken) {
    final var resp =
        feishu
            .docx()
            .v1()
            .documentBlockChildren()
            .batchDelete(
                BatchDeleteDocumentBlockChildrenReq.newBuilder()
                    .documentId(documentId)
                    .blockId(blockId)
                    .documentRevisionId(documentRevisionId)
                    .clientToken(clientToken)
                    .batchDeleteDocumentBlockChildrenReqBody(
                        BatchDeleteDocumentBlockChildrenReqBody.newBuilder()
                            .startIndex(startIndex)
                            .endIndex(endIndex)
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to delete children [{}, {}) of block {} in document {}: {}, {}",
          startIndex,
          endIndex,
          blockId,
          documentId,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to delete document block children: " + resp.getMsg());
    }
    log.info(
        "Deleted children [{}, {}) of block {} in document {}",
        startIndex,
        endIndex,
        blockId,
        documentId);
  }

  @SneakyThrows
  public String convertToBlocks(final String contentType, final String content) {
    return Jsons.DEFAULT.toJson(convertToBlockData(contentType, content));
  }

  /**
   * The conversion as the SDK models it, for a caller that goes on to insert the blocks rather than
   * show them to anybody.
   *
   * <p>Separate from {@link #convertToBlocks} so that the blocks, the first-level ids and the image
   * urls stay objects on the way from conversion to insertion. Serializing them only to parse them
   * again is what the model used to be asked to do by hand.
   */
  @SneakyThrows
  public ConvertDocumentRespBody convertToBlockData(
      final String contentType, final String content) {
    final var resp =
        feishu
            .docx()
            .v1()
            .document()
            .convert(
                ConvertDocumentReq.newBuilder()
                    .convertDocumentReqBody(
                        ConvertDocumentReqBody.newBuilder()
                            .contentType(contentType)
                            .content(content)
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to convert {} content to blocks: {}, {}",
          contentType,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to convert content to blocks: " + resp.getMsg());
    }
    log.info("Converted {} content to {} block(s)", contentType, resp.getData().getBlocks().length);
    return resp.getData();
  }
}
