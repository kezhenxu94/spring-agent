package me.kezhenxu94.springagent.bot.feishu.model.spreadsheet;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import me.kezhenxu94.springagent.bot.feishu.sheet.ValueRange;

@Jacksonized
@Builder(toBuilder = true)
public record GetValueRangeDTO(int revision, String spreadsheetToken, ValueRange valueRange) {}
