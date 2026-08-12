package me.kezhenxu94.springagent.integration.feishu.model.spreadsheet;

import java.util.List;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@Jacksonized
@Builder(toBuilder = true)
public record GetProtectedRangesDTO(List<ProtectedRange> protectedRanges) {}
