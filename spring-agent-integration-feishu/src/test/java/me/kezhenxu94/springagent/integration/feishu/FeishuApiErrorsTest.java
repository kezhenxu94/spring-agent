package me.kezhenxu94.springagent.integration.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.lark.oapi.core.response.error.Error;
import com.lark.oapi.core.response.error.ErrorFieldViolation;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeishuApiErrorsTest {

  @Test
  @DisplayName("a refusal with no error object is its code and message")
  void codeAndMessageOnly() {
    final var resp = new CreateMessageResp();
    resp.setCode(230002);
    resp.setMsg("Bot is not in the chat");

    assertThat(FeishuApiErrors.describe(resp)).isEqualTo("code 230002: Bot is not in the chat");
  }

  @Test
  @DisplayName("every field Feishu objected to is named, with the troubleshooter and the log id")
  void fieldViolationsTroubleshooterAndLogId() {
    final var violation = new ErrorFieldViolation();
    violation.setField("body.elements[1].rows[0].owner");
    violation.setValue("x");
    violation.setDescription("column not declared");
    final var error = new Error();
    error.setFieldViolations(new ErrorFieldViolation[] {violation});
    error.setTroubleshooter("https://open.feishu.cn/search?log_id=log_1");
    error.setLogId("log_1");
    final var resp = new CreateMessageResp();
    resp.setCode(230099);
    resp.setMsg("Failed to create card content");
    resp.setError(error);

    assertThat(FeishuApiErrors.describe(resp))
        .isEqualTo(
            """
            code 230099: Failed to create card content
            - body.elements[1].rows[0].owner = x: column not declared
            https://open.feishu.cn/search?log_id=log_1
            log_id: log_1\
            """);
  }
}
