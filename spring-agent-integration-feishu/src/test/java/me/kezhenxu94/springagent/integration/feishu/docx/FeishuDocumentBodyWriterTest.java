package me.kezhenxu94.springagent.integration.feishu.docx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.service.docx.v1.model.Block;
import com.lark.oapi.service.docx.v1.model.BlockIdRelation;
import com.lark.oapi.service.docx.v1.model.BlockIdToImageUrl;
import com.lark.oapi.service.docx.v1.model.ConvertDocumentRespBody;
import com.lark.oapi.service.docx.v1.model.CreateDocumentBlockDescendantRespBody;
import com.lark.oapi.service.docx.v1.model.Image;
import com.lark.oapi.service.docx.v1.model.Table;
import com.lark.oapi.service.docx.v1.model.TableMergeInfo;
import com.lark.oapi.service.docx.v1.model.TableProperty;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The three things this class does so that the model does not have to. Each one used to be a
 * sentence in a tool description that a run could simply not follow.
 */
@ExtendWith(MockitoExtension.class)
class FeishuDocumentBodyWriterTest {

  @Mock private FeishuDocxService feishuDocxService;
  @Mock private FeishuDriveService feishuDriveService;

  private FeishuDocumentBodyWriter writer;

  @BeforeEach
  void setUp() {
    writer = new FeishuDocumentBodyWriter(feishuDocxService, feishuDriveService);
  }

  @Test
  @DisplayName("a body that fits goes in with one call, and the block tree never comes back")
  void singleCall() {
    conversionOf(paragraphs("a", "b", "c"), "a", "b", "c");
    insertsSucceed();

    final var written = writer.write("doc", "doc", "markdown", "hello", null, null, null, null);

    verify(feishuDocxService, times(1))
        .createDescendants(any(), any(), any(), any(), any(), any(), any());
    assertThat(written.calls()).isEqualTo(1);
    assertThat(written.blockCount()).isEqualTo(3);
    assertThat(written.firstLevelBlockIds()).containsEntry("a", "real-a");
  }

  @Test
  @DisplayName(
      "the read-only merge info a table carries out of conversion is dropped before insert")
  void stripsMergeInfo() {
    final var table = new Block();
    table.setBlockId("t");
    final var property = new TableProperty();
    property.setRowSize(2);
    property.setMergeInfo(new TableMergeInfo[] {new TableMergeInfo()});
    final var body = new Table();
    body.setProperty(property);
    table.setTable(body);
    conversionOf(new Block[] {table}, "t");
    insertsSucceed();

    writer.write("doc", "doc", "markdown", "| a |", null, null, null, null);

    final var descendants = ArgumentCaptor.forClass(Block[].class);
    verify(feishuDocxService)
        .createDescendants(any(), any(), any(), descendants.capture(), any(), any(), any());
    assertThat(descendants.getValue()[0].getTable().getProperty().getMergeInfo()).isNull();
    // Everything else about the table survives: this drops one field, it does not rebuild it.
    assertThat(descendants.getValue()[0].getTable().getProperty().getRowSize()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "past what one call carries the insert splits at top-level boundaries, never inside one")
  void splitsPastTheLimit() {
    // Two top-level blocks of 600 blocks each: 1200 in all, so they cannot share a call, and
    // neither may be cut in half because a block names its children by id.
    final var blocks = new ArrayList<Block>();
    final var firstLevel = new String[] {"s0", "s1"};
    for (var subtree = 0; subtree < 2; subtree++) {
      final var children = new String[599];
      for (var i = 0; i < 599; i++) {
        children[i] = "s" + subtree + "-c" + i;
        final var child = new Block();
        child.setBlockId(children[i]);
        blocks.add(child);
      }
      final var root = new Block();
      root.setBlockId("s" + subtree);
      root.setChildren(children);
      blocks.add(root);
    }
    conversionOf(blocks.toArray(new Block[0]), firstLevel);
    insertsSucceed();

    final var written = writer.write("doc", "doc", "markdown", "long", 4, null, "token", null);

    final var childrenId = ArgumentCaptor.forClass(String[].class);
    final var index = ArgumentCaptor.forClass(Integer.class);
    final var clientToken = ArgumentCaptor.forClass(String.class);
    verify(feishuDocxService, times(2))
        .createDescendants(
            any(),
            any(),
            childrenId.capture(),
            any(),
            index.capture(),
            any(),
            clientToken.capture());
    assertThat(childrenId.getAllValues().get(0)).containsExactly("s0");
    assertThat(childrenId.getAllValues().get(1)).containsExactly("s1");
    // The second chunk goes after the first rather than on top of it.
    assertThat(index.getAllValues()).containsExactly(4, 5);
    // And it carries an idempotency key of its own: reusing one would have Feishu answer the
    // second call with the first call's result and drop half the document silently.
    assertThat(clientToken.getAllValues()).doesNotHaveDuplicates();
    assertThat(written.calls()).isEqualTo(2);
    assertThat(written.firstLevelBlockIds()).containsOnlyKeys("s0", "s1");
  }

  @Test
  @DisplayName("a single top-level element too big to carry is named, not truncated")
  void oversizedSubtreeIsRefused() {
    final var blocks = new ArrayList<Block>();
    final var children = new String[FeishuDocumentBodyWriter.MAX_BLOCKS_PER_CALL];
    for (var i = 0; i < children.length; i++) {
      children[i] = "c" + i;
      final var child = new Block();
      child.setBlockId(children[i]);
      blocks.add(child);
    }
    final var root = new Block();
    root.setBlockId("huge");
    root.setChildren(children);
    blocks.add(root);
    conversionOf(blocks.toArray(new Block[0]), "huge");

    assertThatThrownBy(() -> writer.write("doc", "doc", "markdown", "x", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be split");
    verify(feishuDocxService, never())
        .createDescendants(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("an image is uploaded against its real block id and put in place, in one go")
  void bindsImages() throws Exception {
    final var image = new Block();
    image.setBlockId("img");
    image.setImage(new Image());
    final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/a.png",
        exchange -> {
          final var body = new byte[] {1, 2, 3};
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    final var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/a.png";
    conversionOf(new Block[] {image}, new String[] {"img"}, imageUrl("img", url));
    insertsSucceed();
    when(feishuDriveService.uploadMedia(any(), eq("docx_image"), eq("real-img"), any()))
        .thenReturn("file-token");

    final var written =
        writer.write("doc", "doc", "markdown", "![a](" + url + ")", null, null, null, null);
    server.stop(0);

    verify(feishuDocxService)
        .patchDocumentBlock(
            eq("doc"),
            eq("real-img"),
            eq("{\"replaceImage\":{\"token\":\"file-token\"}}"),
            any(),
            any());
    assertThat(written.imagesBound()).isEqualTo(1);
    assertThat(written.imageProblems()).isEmpty();
  }

  @Test
  @DisplayName(
      "an image the content gave as a local path the run may not read is reported, not written")
  void reportsUnreadableLocalImage() {
    final var image = new Block();
    image.setBlockId("img");
    image.setImage(new Image());
    conversionOf(new Block[] {image}, "img");
    insertsSucceed();

    final var written =
        writer.write(
            "doc", "doc", "markdown", "![a](/etc/passwd)", null, null, null, source -> null);

    // The document is written; only the picture is missing, and the model is told which.
    assertThat(written.imagesBound()).isZero();
    assertThat(written.imageProblems()).singleElement().asString().contains("/etc/passwd");
    verify(feishuDriveService, never()).uploadMedia(any(), any(), any(), any());
  }

  @Test
  @DisplayName("a local image inside the workspace is uploaded from disk")
  void uploadsLocalImage() throws Exception {
    final var file = File.createTempFile("body-writer-", ".png");
    file.deleteOnExit();
    final var image = new Block();
    image.setBlockId("img");
    image.setImage(new Image());
    conversionOf(new Block[] {image}, "img");
    insertsSucceed();
    when(feishuDriveService.uploadMedia(any(), eq("docx_image"), eq("real-img"), eq(file)))
        .thenReturn("file-token");

    final var written =
        writer.write(
            "doc",
            "doc",
            "markdown",
            "![a](" + file.getAbsolutePath() + ")",
            null,
            null,
            null,
            source -> source.equals(file.getAbsolutePath()) ? file : null);

    assertThat(written.imagesBound()).isEqualTo(1);
    assertThat(written.imageProblems()).isEmpty();
  }

  @Test
  @DisplayName("content that converts to nothing says so rather than writing an empty document")
  void refusesEmptyContent() {
    assertThatThrownBy(() -> writer.write("doc", "doc", "markdown", "  ", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(feishuDocxService, never()).convertToBlockData(any(), any());
  }

  @Test
  @DisplayName("every image source the Markdown names is found, in the order it names them")
  void findsImageSources() {
    assertThat(
            FeishuDocumentBodyWriter.imageSources(
                "text ![one](/a/one.png) more ![](http://x/two.jpg) and ![t](<b c.png> \"title\")"))
        .containsExactly("/a/one.png", "http://x/two.jpg", "b c.png");
  }

  private static Block[] paragraphs(final String... ids) {
    return Arrays.stream(ids)
        .map(
            id -> {
              final var block = new Block();
              block.setBlockId(id);
              return block;
            })
        .toArray(Block[]::new);
  }

  private void conversionOf(final Block[] blocks, final String... firstLevel) {
    conversionOf(blocks, firstLevel, new BlockIdToImageUrl[0]);
  }

  private void conversionOf(
      final Block[] blocks, final String[] firstLevel, final BlockIdToImageUrl... images) {
    final var converted = new ConvertDocumentRespBody();
    converted.setBlocks(blocks);
    converted.setFirstLevelBlockIds(firstLevel);
    converted.setBlockIdToImageUrls(images);
    when(feishuDocxService.convertToBlockData(any(), any())).thenReturn(converted);
  }

  /** Every insert answers with real ids derived from the temporary ones, as Feishu does. */
  private void insertsSucceed() {
    when(feishuDocxService.createDescendants(any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              final Block[] descendants = invocation.getArgument(3);
              final var response = new CreateDocumentBlockDescendantRespBody();
              final var relations = new ArrayList<BlockIdRelation>();
              for (final var block : descendants) {
                final var relation = new BlockIdRelation();
                relation.setTemporaryBlockId(block.getBlockId());
                relation.setBlockId("real-" + block.getBlockId());
                relations.add(relation);
              }
              response.setBlockIdRelations(relations.toArray(new BlockIdRelation[0]));
              return response;
            });
  }

  private static BlockIdToImageUrl imageUrl(final String blockId, final String url) {
    final var entry = new BlockIdToImageUrl();
    entry.setBlockId(blockId);
    entry.setImageUrl(url);
    return entry;
  }
}
