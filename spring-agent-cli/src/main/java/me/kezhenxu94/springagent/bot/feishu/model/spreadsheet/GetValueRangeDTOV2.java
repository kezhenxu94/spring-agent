package me.kezhenxu94.springagent.bot.feishu.model.spreadsheet;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import me.kezhenxu94.springagent.bot.feishu.sheet.ValueRangeV2;

@Jacksonized
@Builder(toBuilder = true)
public record GetValueRangeDTOV2(int revision, String spreadsheetToken, ValueRangeV2 valueRange) {}
