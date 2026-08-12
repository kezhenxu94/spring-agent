package me.kezhenxu94.springagent.bot.feishu.model;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Builder
@Jacksonized
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SendMessageResponseDTO(String rootId, String messageId, String parentId) {}
