package me.kezhenxu94.springagent.integration.feishu.docx;

import com.lark.oapi.service.docx.v1.model.Block;
import com.lark.oapi.service.docx.v1.model.ConvertDocumentRespBody;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.springframework.stereotype.Service;

/**
 * Writes a document body from Markdown or HTML in one go: convert, insert, and bind the images.
 *
 * <p>All of this used to be the model's job, and none of it was ever a decision. Conversion
 * produced a block tree whose only destination was the very next call, so up to a thousand nested
 * block objects were written out by the model and read straight back in — paid for twice in the
 * turn and again in every later turn that replays it. Around that sat three rules the tool
 * descriptions asked the model to carry out from memory: strip the read-only merge info from
 * tables, split past a thousand blocks, and run an upload-then-replace dance for every image.
 * Forgetting any of them produced an error from Feishu that says nothing about which rule was
 * missed. None of the three needs judgement, so none of them belongs in a prompt.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuDocumentBodyWriter {

  /** What one create-descendant call may carry, as the Feishu endpoint limits it. */
  static final int MAX_BLOCKS_PER_CALL = 1000;

  /**
   * Markdown image syntax, capturing the source: the text between the brackets of {@code ![]()}.
   */
  private static final Pattern MARKDOWN_IMAGE =
      Pattern.compile("!\\[[^\\]]*\\]\\(\\s*(?:<([^>]*)>|([^)\\s]+))(?:\\s+\"[^\"]*\")?\\s*\\)");

  private final FeishuDocxService feishuDocxService;
  private final FeishuDriveService feishuDriveService;

  /**
   * Where an image the content names by a local path may be read from.
   *
   * <p>A callback rather than a path list, because whether a run may read a file depends on whose
   * run it is, and only the tool layer holds that. This class must not be able to decide it.
   */
  @FunctionalInterface
  public interface LocalImages {
    /** The file that source names, or null when it is not a local path this run may read. */
    File resolve(String source);
  }

  @Builder
  @Jacksonized
  public record WrittenBody(
      String documentId,
      int blockCount,
      int calls,
      int imagesBound,
      List<String> imageProblems,
      Map<String, String> firstLevelBlockIds) {}

  /**
   * Converts the content and inserts it, returning a summary rather than the tree.
   *
   * <p>What comes back is deliberately small: counts, and the real ids of the first-level blocks so
   * that a caller wanting to touch one afterwards can. The tree itself has already served its
   * purpose by the time this returns, and handing it back would put the payload this exists to
   * avoid straight back into the conversation.
   */
  public WrittenBody write(
      final String documentId,
      final String blockId,
      final String contentType,
      final String content,
      final Integer index,
      final Integer documentRevisionId,
      final String clientToken,
      final LocalImages localImages) {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("There is no content to write.");
    }

    final var converted = feishuDocxService.convertToBlockData(contentType, content);
    final var blocks = converted.getBlocks() == null ? new Block[0] : converted.getBlocks();
    final var firstLevel =
        converted.getFirstLevelBlockIds() == null
            ? new String[0]
            : converted.getFirstLevelBlockIds();
    if (blocks.length == 0 || firstLevel.length == 0) {
      throw new IllegalStateException(
          "The content converted to no blocks at all. Check that it is really "
              + contentType
              + " and not empty.");
    }

    stripMergeInfo(blocks);

    final var byTemporaryId = new HashMap<String, Block>();
    for (final var block : blocks) {
      byTemporaryId.put(block.getBlockId(), block);
    }

    final var relations = new HashMap<String, String>();
    var calls = 0;
    var inserted = 0;
    for (final var chunk : chunk(firstLevel, byTemporaryId)) {
      final var descendants = closureOf(chunk, byTemporaryId);
      final var response =
          feishuDocxService.createDescendants(
              documentId,
              blockId,
              chunk.toArray(new String[0]),
              descendants.toArray(new Block[0]),
              // A chunk after the first goes where the last one ended. Appending (-1, the default)
              // already does that, so only an explicit position has to be walked forward. Written
              // out rather than as a conditional expression, which would unbox both branches and
              // so fail on the null that means "no position given".
              positionOf(index, inserted),
              documentRevisionId,
              // The idempotency key covers one call, so reusing it across chunks would have the
              // second chunk answered with the first chunk's result and silently dropped.
              clientToken == null || calls == 0 ? clientToken : clientToken + "-" + calls);
      if (response.getBlockIdRelations() != null) {
        for (final var relation : response.getBlockIdRelations()) {
          relations.put(relation.getTemporaryBlockId(), relation.getBlockId());
        }
      }
      calls++;
      inserted += chunk.size();
      if (calls > 1) {
        log.info(
            "Wrote chunk {} of the body of document {}: {} first-level block(s)",
            calls,
            documentId,
            chunk.size());
      }
    }

    final var problems = new ArrayList<String>();
    final var imagesBound =
        bindImages(
            documentId, content, contentType, blocks, converted, relations, localImages, problems);

    final var firstLevelReal = new LinkedHashMap<String, String>();
    for (final var temporary : firstLevel) {
      firstLevelReal.put(temporary, relations.getOrDefault(temporary, temporary));
    }

    return WrittenBody.builder()
        .documentId(documentId)
        .blockCount(blocks.length)
        .calls(calls)
        .imagesBound(imagesBound)
        .imageProblems(problems)
        .firstLevelBlockIds(firstLevelReal)
        .build();
  }

  /** Where a chunk goes, given where the caller asked the first one to go. */
  private static Integer positionOf(final Integer index, final int alreadyInserted) {
    if (index == null || index < 0) {
      return index;
    }
    return index + alreadyInserted;
  }

  /**
   * Drops the merge info every table block carries out of the conversion.
   *
   * <p>It is a read-only description of which cells are merged, and the write endpoint rejects a
   * table that states it. There is no case in which keeping it is right, which is why this is done
   * here rather than asked for in a tool description.
   */
  private static void stripMergeInfo(final Block[] blocks) {
    for (final var block : blocks) {
      if (block.getTable() != null && block.getTable().getProperty() != null) {
        block.getTable().getProperty().setMergeInfo(null);
      }
    }
  }

  /**
   * The first-level ids grouped into runs small enough for one call.
   *
   * <p>Split at first-level boundaries and nowhere else: a block refers to its children by id, so a
   * cut inside a subtree would send half a table and leave the other half referring to blocks that
   * were never created. A single first-level block whose own subtree is over the limit therefore
   * cannot be split at all, and saying so is better than sending something Feishu will reject for a
   * reason that does not name the cause.
   */
  private static List<List<String>> chunk(
      final String[] firstLevel, final Map<String, Block> byTemporaryId) {
    final var chunks = new ArrayList<List<String>>();
    var current = new ArrayList<String>();
    var currentSize = 0;
    for (final var id : firstLevel) {
      final var size = closureOf(List.of(id), byTemporaryId).size();
      if (size > MAX_BLOCKS_PER_CALL) {
        throw new IllegalArgumentException(
            "One top-level element of the content became "
                + size
                + " blocks, over the "
                + MAX_BLOCKS_PER_CALL
                + " one call may carry, and it cannot be split without breaking it apart. Split"
                + " that element in the content itself — a very long table or list is the usual"
                + " cause — and write the body again.");
      }
      if (!current.isEmpty() && currentSize + size > MAX_BLOCKS_PER_CALL) {
        chunks.add(current);
        current = new ArrayList<>();
        currentSize = 0;
      }
      current.add(id);
      currentSize += size;
    }
    if (!current.isEmpty()) {
      chunks.add(current);
    }
    return chunks;
  }

  /** The blocks reachable from these ids, themselves included, in a stable order. */
  private static List<Block> closureOf(
      final List<String> roots, final Map<String, Block> byTemporaryId) {
    final var collected = new LinkedHashMap<String, Block>();
    final var pending = new ArrayList<>(roots);
    while (!pending.isEmpty()) {
      final var id = pending.remove(0);
      // Already collected means already walked; a conversion should not produce a cycle, but a
      // walk that assumes so would hang rather than fail.
      if (collected.containsKey(id)) {
        continue;
      }
      final var block = byTemporaryId.get(id);
      if (block == null) {
        continue;
      }
      collected.put(id, block);
      if (block.getChildren() != null) {
        pending.addAll(List.of(block.getChildren()));
      }
    }
    return List.copyOf(collected.values());
  }

  /**
   * Uploads each image the content carries and binds it to the block that was created for it.
   *
   * <p>Conversion leaves an image block with no content: what it hands back is a temporary address
   * to fetch the image from, and the block only becomes an image once a file has been uploaded
   * against it and the block updated to name the upload. An image the content gave as a local path
   * has no temporary address at all, so it is matched by position — the images in the content and
   * the image blocks in the conversion are in the same order — and that pairing is only trusted
   * when the two counts agree.
   *
   * <p>A failure here leaves a document that is written but missing a picture, which is worth
   * reporting and not worth undoing, so each one is collected rather than thrown.
   */
  private int bindImages(
      final String documentId,
      final String content,
      final String contentType,
      final Block[] blocks,
      final ConvertDocumentRespBody converted,
      final Map<String, String> relations,
      final LocalImages localImages,
      final List<String> problems) {
    final var urlByTemporaryId = new HashMap<String, String>();
    if (converted.getBlockIdToImageUrls() != null) {
      for (final var entry : converted.getBlockIdToImageUrls()) {
        urlByTemporaryId.put(entry.getBlockId(), entry.getImageUrl());
      }
    }

    final var imageBlocks = new ArrayList<String>();
    for (final var block : blocks) {
      if (block.getImage() != null) {
        imageBlocks.add(block.getBlockId());
      }
    }
    if (imageBlocks.isEmpty()) {
      return 0;
    }

    final var sources =
        "markdown".equalsIgnoreCase(contentType) ? imageSources(content) : List.<String>of();
    // Only trusted when they line up. Where they do not, the images with a temporary address are
    // still bound; the rest are reported rather than guessed at.
    final var sourceByTemporaryId = new HashMap<String, String>();
    if (sources.size() == imageBlocks.size()) {
      for (var i = 0; i < imageBlocks.size(); i++) {
        sourceByTemporaryId.put(imageBlocks.get(i), sources.get(i));
      }
    }

    var bound = 0;
    for (final var temporaryId : imageBlocks) {
      final var realId = relations.get(temporaryId);
      if (realId == null) {
        problems.add("An image block was not created, so nothing could be bound to it.");
        continue;
      }
      File file = null;
      var temporary = false;
      try {
        final var url = urlByTemporaryId.get(temporaryId);
        final var source = sourceByTemporaryId.get(temporaryId);
        if (url != null) {
          file = download(url);
          temporary = true;
        } else if (source != null && localImages != null) {
          file = localImages.resolve(source);
          if (file == null) {
            problems.add(
                "Left the image "
                    + source
                    + " out: it is not a file inside a workspace this run can read. Put it in the"
                    + " workspace, or give it as a URL.");
            continue;
          }
        } else {
          problems.add(
              "An image had neither a fetchable address nor a local file, so its block is empty.");
          continue;
        }
        final var token =
            feishuDriveService.uploadMedia(file.getName(), "docx_image", realId, file);
        feishuDocxService.patchDocumentBlock(
            documentId, realId, "{\"replaceImage\":{\"token\":\"" + token + "\"}}", null, null);
        bound++;
      } catch (Exception e) {
        log.warn("Failed to bind an image to block {} of document {}", realId, documentId, e);
        problems.add("Could not put one image in place: " + e.getMessage());
      } finally {
        if (temporary && file != null && !file.delete()) {
          log.debug("Could not delete the temporary image file {}", file);
        }
      }
    }
    return bound;
  }

  /** Every image source the Markdown names, in the order it names them. */
  static List<String> imageSources(final String content) {
    final var sources = new ArrayList<String>();
    final var matcher = MARKDOWN_IMAGE.matcher(content);
    while (matcher.find()) {
      // Angle brackets are how Markdown writes a source with a space in it; either group matched,
      // never both.
      sources.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
    }
    return sources;
  }

  /** The image behind a temporary address, in a file of its own that the caller deletes. */
  private static File download(final String url) throws Exception {
    final var client = HttpClient.newHttpClient();
    final var response =
        client.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("Fetching the image returned HTTP " + response.statusCode());
    }
    final var file = Files.createTempFile("feishu-doc-image-", suffixOf(url));
    Files.write(file, response.body());
    return file.toFile();
  }

  /** The extension of what the address names, which is what Feishu keys the image type off. */
  private static String suffixOf(final String url) {
    final var name = Path.of(URI.create(url).getPath()).getFileName();
    final var text = name == null ? "" : name.toString();
    final var dot = text.lastIndexOf('.');
    return dot < 0 || dot == text.length() - 1 ? ".png" : text.substring(dot);
  }
}
