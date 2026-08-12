package me.kezhenxu94.springagent.integration.feishu.model;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@Builder
@Jacksonized
public record FeishuResponse<T>(int code, String msg, T data) {}
