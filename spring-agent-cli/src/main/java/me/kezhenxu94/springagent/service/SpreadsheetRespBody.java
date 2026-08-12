package me.kezhenxu94.springagent.service;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Data
@Jacksonized
@Builder(toBuilder = true)
public class SpreadsheetRespBody {
  private ValueRange valueRange;
}
