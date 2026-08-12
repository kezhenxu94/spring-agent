package me.kezhenxu94.springagent.integration.feishu.tools;

import com.google.gson.annotations.SerializedName;
import com.lark.oapi.core.annotation.Query;
import lombok.Builder;

// Local @Query POJO instead of com.lark.oapi.service.im.v1.model.ListMessageReq: the upstream
// class lacks card_msg_content_type, and the SDK's ReqTranslator reads query fields with
// req.getClass().getDeclaredFields() (no superclass walk), so subclassing would silently drop
// the inherited fields.
@Builder
class ListMessageQuery {
  @Query
  @SerializedName("container_id_type")
  String containerIdType;

  @Query
  @SerializedName("container_id")
  String containerId;

  @Query
  @SerializedName("start_time")
  String startTime;

  @Query
  @SerializedName("end_time")
  String endTime;

  @Query
  @SerializedName("sort_type")
  String sortType;

  @Query
  @SerializedName("page_size")
  Integer pageSize;

  @Query
  @SerializedName("page_token")
  String pageToken;

  @Query
  @SerializedName("card_msg_content_type")
  String cardMsgContentType;
}
