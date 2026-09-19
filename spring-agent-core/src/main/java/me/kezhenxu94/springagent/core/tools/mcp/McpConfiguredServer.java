package me.kezhenxu94.springagent.core.tools.mcp;

/**
 * One server this application configures for everyone, as a caller outside this package sees it.
 *
 * <p>A record of its own rather than Spring AI's {@code ConnectionParameters}, because that type
 * lives in the MCP client's auto-configuration jar and every consumer of {@link McpServerRegistry}
 * would then need it on its compile classpath to read a name and a URL — which the browser's {@code
 * McpController} has no other reason to carry, and which {@code spring.ai.mcp.client.enabled=false}
 * makes absent at runtime anyway. Where the URL comes from is settled once, in the registry, rather
 * than by each caller reassembling the endpoint path.
 *
 * @param name what it is configured as, and what a mutation naming it is refused for
 * @param url where its requests actually go, endpoint path included
 */
public record McpConfiguredServer(String name, String url) {}
