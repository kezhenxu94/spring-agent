package me.kezhenxu94.springagent.core.tools.mcp;

import java.util.List;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;

/**
 * A server that was registered, and what it turned out to offer.
 *
 * <p>The tool names come from the registration probe rather than from a second call, because the
 * probe is the only moment the connection is known to work — and they are the whole of what makes
 * the answer useful. "Registered" tells nobody whether the credential was right; "registered, 14
 * tools" does.
 *
 * @param config the row as it was stored, with its defaults settled
 * @param toolNames what the server listed, possibly empty for a server that offers only resources
 */
public record McpRegistration(McpServerConfig config, List<String> toolNames) {}
