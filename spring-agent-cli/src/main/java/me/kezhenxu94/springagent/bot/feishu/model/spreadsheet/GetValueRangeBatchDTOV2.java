package me.kezhenxu94.springagent.bot.feishu.model.spreadsheet;

import java.util.List;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import me.kezhenxu94.springagent.bot.feishu.sheet.ValueRangeV2;

@Jacksonized
@Builder(toBuilder = true)
public record GetValueRangeBatchDTOV2(
    int revision, String spreadsheetToken, int totalCells, List<ValueRangeV2> valueRanges) {}
