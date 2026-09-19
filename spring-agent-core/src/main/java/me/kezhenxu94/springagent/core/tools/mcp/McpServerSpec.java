package me.kezhenxu94.springagent.core.tools.mcp;

import java.util.Map;

/**
 * What a caller is asking to register, before anything has been checked.
 *
 * <p>A record rather than eight parameters because there are two callers with nothing else in
 * common — the model, through {@code McpServerManagementTools}, and a person, through the browser's
 * {@code McpController} — and a positional list of seven optional strings is an argument order two
 * call sites will eventually disagree about. Every field but the first two is optional and blank is
 * the same as absent; {@link McpServerRegistry} is what settles the defaults, so neither caller has
 * to know what they are.
 *
 * @param name the server's name, unique for one owner, and what everything else addresses it by
 * @param url the streamable HTTP endpoint
 * @param headers sent on every request to it, authentication among them, or null to store none
 * @param title reported to the server as {@code clientInfo.title}; blank means the name
 * @param version reported to the server as {@code clientInfo.version}; blank means 1.0.0
 * @param description free prose for whoever reads the list
 * @param websiteUrl where this integration is documented, for whoever reads the list
 * @param toolPrefix what every tool of this server is named after; blank means a hash of the name
 */
public record McpServerSpec(
    String name,
    String url,
    Map<String, String> headers,
    String title,
    String version,
    String description,
    String websiteUrl,
    String toolPrefix) {}
