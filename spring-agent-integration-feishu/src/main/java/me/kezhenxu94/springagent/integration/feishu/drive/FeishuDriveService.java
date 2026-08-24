package me.kezhenxu94.springagent.integration.feishu.drive;

import com.lark.oapi.Client;
import com.lark.oapi.service.drive.v1.model.UploadAllMediaReq;
import com.lark.oapi.service.drive.v1.model.UploadAllMediaReqBody;
import java.io.File;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Drive calls that belong to no one document type.
 *
 * <p>Uploading a medium is the same endpoint whatever will hold the result — a document's image
 * block, a bitable's attachment cell — and only {@code parentType} and {@code parentNode} say
 * which. It lives here rather than in one of those services so that neither has to reach into the
 * other.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuDriveService {

  private final Client feishu;

  /**
   * Uploads a file and returns the {@code file_token} that whatever will hold it refers to it by.
   *
   * <p>The token is bound to {@code parentNode}: a token uploaded against one document or base is
   * rejected by any other, so the upload has to name the node the medium is destined for rather
   * than being done once and reused.
   */
  @SneakyThrows
  public String uploadMedia(
      final String fileName, final String parentType, final String parentNode, final File file) {
    final var resp =
        feishu
            .drive()
            .v1()
            .media()
            .uploadAll(
                UploadAllMediaReq.newBuilder()
                    .uploadAllMediaReqBody(
                        UploadAllMediaReqBody.newBuilder()
                            .fileName(fileName)
                            .parentType(parentType)
                            .parentNode(parentNode)
                            .size((int) file.length())
                            .file(file)
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to upload media '{}' for parent node {}: {}, {}",
          fileName,
          parentNode,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to upload media: " + resp.getMsg());
    }
    final var fileToken = resp.getData().getFileToken();
    log.info(
        "Uploaded media '{}' for parent node {}: fileToken={}", fileName, parentNode, fileToken);
    return fileToken;
  }
}
