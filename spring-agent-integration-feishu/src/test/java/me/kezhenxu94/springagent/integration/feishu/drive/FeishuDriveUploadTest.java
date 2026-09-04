package me.kezhenxu94.springagent.integration.feishu.drive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.drive.DriveService;
import com.lark.oapi.service.drive.v1.V1;
import com.lark.oapi.service.drive.v1.model.UploadAllFileReq;
import com.lark.oapi.service.drive.v1.model.UploadAllFileResp;
import com.lark.oapi.service.drive.v1.model.UploadAllFileRespBody;
import com.lark.oapi.service.drive.v1.model.UploadFinishFileReq;
import com.lark.oapi.service.drive.v1.model.UploadFinishFileResp;
import com.lark.oapi.service.drive.v1.model.UploadFinishFileRespBody;
import com.lark.oapi.service.drive.v1.model.UploadPartFileReq;
import com.lark.oapi.service.drive.v1.model.UploadPartFileResp;
import com.lark.oapi.service.drive.v1.model.UploadPrepareFileReq;
import com.lark.oapi.service.drive.v1.model.UploadPrepareFileResp;
import com.lark.oapi.service.drive.v1.model.UploadPrepareFileRespBody;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Adler32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which of Feishu's two upload flows a file takes, and what the chunked one actually sends.
 *
 * <p>Worth asserting because neither is visible from the outside: both answer with a file token, so
 * a chunked upload that sends the same block twice, skips the last one or reports the wrong count
 * produces a file that is silently not the file that was uploaded.
 */
@ExtendWith(MockitoExtension.class)
class FeishuDriveUploadTest {

  /** Feishu's own block size, which is what it answers a pre-upload with. */
  private static final int BLOCK_SIZE = 4 * 1024 * 1024;

  private static final int SINGLE_UPLOAD_LIMIT = 20 * 1024 * 1024;

  @Mock private Client feishu;
  @Mock private DriveService driveService;
  @Mock private V1 driveV1;
  @Mock private com.lark.oapi.service.drive.v1.resource.File fileResource;

  private FeishuDriveService service;

  @TempDir Path dir;

  @BeforeEach
  void setUp() {
    lenient().when(feishu.drive()).thenReturn(driveService);
    lenient().when(driveService.v1()).thenReturn(driveV1);
    lenient().when(driveV1.file()).thenReturn(fileResource);
    service = new FeishuDriveService(feishu, new JsonMapper());
  }

  /** Bytes a wrong block boundary cannot be mistaken for the right one in. */
  private static byte[] bytes(final int size) {
    final var content = new byte[size];
    for (var i = 0; i < size; i++) {
      content[i] = (byte) (i % 251);
    }
    return content;
  }

  private static String adler32(final byte[] bytes) {
    final var checksum = new Adler32();
    checksum.update(bytes);
    return Long.toString(checksum.getValue());
  }

  private Path aFileOf(final int size) throws Exception {
    final var file = dir.resolve("report.xlsx");
    Files.write(file, bytes(size));
    return file;
  }

  private static UploadAllFileResp uploadedAtOnce(final String fileToken) {
    final var body = new UploadAllFileRespBody();
    body.setFileToken(fileToken);
    final var resp = new UploadAllFileResp();
    resp.setData(body);
    return resp;
  }

  private static UploadPrepareFileResp prepared(final String uploadId, final int blockNum) {
    final var body = new UploadPrepareFileRespBody();
    body.setUploadId(uploadId);
    body.setBlockSize(BLOCK_SIZE);
    body.setBlockNum(blockNum);
    final var resp = new UploadPrepareFileResp();
    resp.setData(body);
    return resp;
  }

  private static UploadFinishFileResp finished(final String fileToken) {
    final var body = new UploadFinishFileRespBody();
    body.setFileToken(fileToken);
    final var resp = new UploadFinishFileResp();
    resp.setData(body);
    return resp;
  }

  @Test
  @DisplayName("a file Feishu takes in one call is not chunked")
  void uploadsASmallFileInOneCall() throws Exception {
    final var file = aFileOf(1024);
    when(fileResource.uploadAll(any())).thenReturn(uploadedAtOnce("boxcnWHOLE"));

    final var token = service.uploadFile("report.xlsx", "fldcnFOLDER", file.toFile());

    assertThat(token).isEqualTo("boxcnWHOLE");
    verify(fileResource, never()).uploadPrepare(any());

    final var req = ArgumentCaptor.forClass(UploadAllFileReq.class);
    verify(fileResource).uploadAll(req.capture());
    final var body = req.getValue().getUploadAllFileReqBody();
    assertThat(body.getFileName()).isEqualTo("report.xlsx");
    assertThat(body.getParentType()).isEqualTo("explorer");
    assertThat(body.getParentNode()).isEqualTo("fldcnFOLDER");
    assertThat(body.getSize()).isEqualTo(1024);
  }

  @Test
  @DisplayName("a larger file is sent as the blocks the pre-upload asked for, in order")
  void uploadsALargeFileInBlocks() throws Exception {
    final var size = SINGLE_UPLOAD_LIMIT + 1;
    final var file = aFileOf(size);
    when(fileResource.uploadPrepare(any())).thenReturn(prepared("70001", 6));
    when(fileResource.uploadFinish(any())).thenReturn(finished("boxcnCHUNKED"));

    // Read as each block is sent, because the temporary file it travels in is deleted straight
    // after the call and there is nothing left to inspect afterwards.
    final var sent = new ArrayList<byte[]>();
    final var seqs = new ArrayList<Integer>();
    final var declaredSizes = new ArrayList<Integer>();
    final var checksums = new ArrayList<String>();
    when(fileResource.uploadPart(any()))
        .thenAnswer(
            invocation -> {
              final var body =
                  invocation.getArgument(0, UploadPartFileReq.class).getUploadPartFileReqBody();
              assertThat(body.getUploadId()).isEqualTo("70001");
              sent.add(Files.readAllBytes(body.getFile().toPath()));
              seqs.add(body.getSeq());
              declaredSizes.add(body.getSize());
              checksums.add(body.getChecksum());
              return new UploadPartFileResp();
            });

    final var token = service.uploadFile("report.xlsx", "fldcnFOLDER", file.toFile());

    assertThat(token).isEqualTo("boxcnCHUNKED");
    verify(fileResource, never()).uploadAll(any());
    assertThat(seqs).containsExactly(0, 1, 2, 3, 4, 5);
    assertThat(declaredSizes)
        .containsExactly(BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, BLOCK_SIZE, 1);

    final var content = bytes(size);
    final var rejoined = new java.io.ByteArrayOutputStream();
    for (final var block : sent) {
      rejoined.write(block);
    }
    assertThat(rejoined.toByteArray()).isEqualTo(content);
    assertThat(checksums)
        .containsExactlyElementsOf(
            List.of(
                adler32(java.util.Arrays.copyOfRange(content, 0, BLOCK_SIZE)),
                adler32(java.util.Arrays.copyOfRange(content, BLOCK_SIZE, 2 * BLOCK_SIZE)),
                adler32(java.util.Arrays.copyOfRange(content, 2 * BLOCK_SIZE, 3 * BLOCK_SIZE)),
                adler32(java.util.Arrays.copyOfRange(content, 3 * BLOCK_SIZE, 4 * BLOCK_SIZE)),
                adler32(java.util.Arrays.copyOfRange(content, 4 * BLOCK_SIZE, 5 * BLOCK_SIZE)),
                adler32(java.util.Arrays.copyOfRange(content, 5 * BLOCK_SIZE, size))));

    final var prepare = ArgumentCaptor.forClass(UploadPrepareFileReq.class);
    verify(fileResource).uploadPrepare(prepare.capture());
    assertThat(prepare.getValue().getFileUploadInfo().getSize()).isEqualTo(size);
    assertThat(prepare.getValue().getFileUploadInfo().getParentNode()).isEqualTo("fldcnFOLDER");

    // The count has to be the one the pre-upload named, not the number of calls made: Feishu
    // matches them and fails the upload when they differ.
    final var finish = ArgumentCaptor.forClass(UploadFinishFileReq.class);
    verify(fileResource).uploadFinish(finish.capture());
    assertThat(finish.getValue().getUploadFinishFileReqBody().getBlockNum()).isEqualTo(6);
    assertThat(finish.getValue().getUploadFinishFileReqBody().getUploadId()).isEqualTo("70001");
  }

  @Test
  @DisplayName("an empty file is refused here rather than by Feishu's 'params error'")
  void refusesAnEmptyFile() throws Exception {
    final var file = aFileOf(0);

    assertThatThrownBy(() -> service.uploadFile("report.xlsx", "fldcnFOLDER", file.toFile()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty");
  }
}
