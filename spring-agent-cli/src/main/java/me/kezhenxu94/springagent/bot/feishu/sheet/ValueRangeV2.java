package me.kezhenxu94.springagent.bot.feishu.sheet;

import java.util.List;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import me.kezhenxu94.springagent.bot.feishu.sheet.ValueRange.Range;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Jacksonized
@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ValueRangeV2(Range range, List<List<JsonNode>> values) {}
