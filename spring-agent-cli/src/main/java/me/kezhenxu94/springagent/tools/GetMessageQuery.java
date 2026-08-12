package me.kezhenxu94.springagent.tools;

import com.google.gson.annotations.SerializedName;
import com.lark.oapi.core.annotation.Query;
import lombok.Builder;

@Builder
class GetMessageQuery {
  @Query
  @SerializedName("user_id_type")
  String userIdType;

  @Query
  @SerializedName("card_msg_content_type")
  String cardMsgContentType;
}
