package me.kezhenxu94.springagent.integration.feishu;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@Builder
@Jacksonized
public record TenantAccessToken(
    int code, String msg, @JsonAlias("tenant_access_token") String tenantAccessToken, int expire) {}
