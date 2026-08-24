package me.kezhenxu94.springagent.integration.feishu.bitable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.service.bitable.BitableService;
import com.lark.oapi.service.bitable.v1.V1;
import com.lark.oapi.service.bitable.v1.model.BatchCreateAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.BatchCreateAppTableRecordResp;
import com.lark.oapi.service.bitable.v1.model.BatchCreateAppTableRecordRespBody;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableRecordResp;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableRecordRespBody;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * How a record's {@code fields} object survives the trip from the model's JSON into the request
 * Feishu receives. Nothing else in the module reads JSON that Gson then has to write back out
 * unchanged, and getting it wrong corrupts values rather than failing, so it is asserted on its
 * own.
 */
@ExtendWith(MockitoExtension.class)
class FeishuBitableServiceTest {

  @Mock private Client feishu;
  @Mock private BitableService bitableService;
  @Mock private V1 bitableV1;

  @Mock private com.lark.oapi.service.bitable.v1.resource.AppTableRecord appTableRecordResource;

  private FeishuBitableService service;

  @BeforeEach
  void setUp() throws Exception {
    lenient().when(feishu.bitable()).thenReturn(bitableService);
    lenient().when(bitableService.v1()).thenReturn(bitableV1);
    lenient().when(bitableV1.appTableRecord()).thenReturn(appTableRecordResource);

    final var respBody = new CreateAppTableRecordRespBody();
    respBody.setRecord(com.lark.oapi.service.bitable.v1.model.AppTableRecord.newBuilder().build());
    final var resp = new CreateAppTableRecordResp();
    resp.setData(respBody);
    lenient().when(appTableRecordResource.create(any())).thenReturn(resp);

    service = new FeishuBitableService(feishu);
  }

  @Test
  @DisplayName(
      "a date field's millisecond timestamp reaches Feishu as the integer it was written as")
  void timestampsAreNotWidenedToDouble() throws Exception {
    // Gson's adapter for Object reads every JSON number as a Double, which would send this as
    // 1.702449755E12. Feishu wants the integer.
    service.createRecord("appToken", "tblA", "{\"Due\": 1702449755000, \"Rating\": 3}", null, null);

    final var captor = ArgumentCaptor.forClass(CreateAppTableRecordReq.class);
    verify(appTableRecordResource).create(captor.capture());
    final var body = Jsons.DEFAULT.toJson(captor.getValue().getAppTableRecord());

    assertThat(body).contains("1702449755000").contains("\"Rating\":3");
    assertThat(body).doesNotContain("E12").doesNotContain("3.0");
  }

  @Test
  @DisplayName("the nested shapes a person, url or link field takes round-trip verbatim")
  void nestedFieldShapesRoundTrip() throws Exception {
    service.createRecord(
        "appToken",
        "tblA",
        "{\"Owner\": [{\"id\": \"ou_abc\"}], \"Link\": {\"text\": \"PR\", \"link\": \"https://x\"},"
            + " \"Parent\": [\"recA\"], \"Done\": false}",
        null,
        null);

    final var captor = ArgumentCaptor.forClass(CreateAppTableRecordReq.class);
    verify(appTableRecordResource).create(captor.capture());
    final var body = Jsons.DEFAULT.toJson(captor.getValue().getAppTableRecord());

    assertThat(body)
        .contains("[{\"id\":\"ou_abc\"}]")
        .contains("{\"text\":\"PR\",\"link\":\"https://x\"}")
        .contains("[\"recA\"]")
        .contains("\"Done\":false");
  }

  @Test
  @DisplayName("a batch write carries each element's record_id and its own fields")
  void batchRecordsKeepTheirIds() throws Exception {
    final var respBody = new BatchCreateAppTableRecordRespBody();
    respBody.setRecords(new com.lark.oapi.service.bitable.v1.model.AppTableRecord[0]);
    final var resp = new BatchCreateAppTableRecordResp();
    resp.setData(respBody);
    when(appTableRecordResource.batchCreate(any())).thenReturn(resp);

    service.batchCreateRecords(
        "appToken",
        "tblA",
        "[{\"record_id\": \"recA\", \"fields\": {\"Title\": \"a\"}},"
            + " {\"fields\": {\"Title\": \"b\"}}]",
        null,
        null);

    final var captor = ArgumentCaptor.forClass(BatchCreateAppTableRecordReq.class);
    verify(appTableRecordResource).batchCreate(captor.capture());
    final var records = captor.getValue().getBatchCreateAppTableRecordReqBody().getRecords();

    assertThat(records).hasSize(2);
    assertThat(records[0].getRecordId()).isEqualTo("recA");
    assertThat(records[0].getFields()).containsOnlyKeys("Title");
    assertThat(records[1].getRecordId()).isNull();
  }

  @Test
  @DisplayName("a batch update insists that every element names the record it updates")
  void batchUpdateNeedsRecordIds() {
    assertThatThrownBy(
            () ->
                service.batchUpdateRecords(
                    "appToken", "tblA", "[{\"fields\": {\"Title\": \"a\"}}]", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("record_id");
  }

  @Test
  @DisplayName("an element with no fields object is rejected by position rather than sent")
  void recordsWithoutFieldsAreRejected() {
    assertThatThrownBy(
            () ->
                service.batchCreateRecords("appToken", "tblA", "[{\"Title\": \"a\"}]", null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("element 0");
  }

  @Test
  @DisplayName("a batch larger than Feishu accepts is refused before the call is made")
  void batchOverTheLimitIsRefused() {
    final var records =
        IntStream.range(0, 1001)
            .mapToObj(i -> "{\"fields\": {\"Title\": \"" + i + "\"}}")
            .collect(Collectors.joining(",", "[", "]"));

    assertThatThrownBy(() -> service.batchCreateRecords("appToken", "tblA", records, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At most 1000 records");
  }

  @Test
  @DisplayName("a fields argument that is not JSON at all is refused by name")
  void invalidJsonIsRefused() {
    assertThatThrownBy(() -> service.createRecord("appToken", "tblA", "null", null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fieldsJson");
  }
}
