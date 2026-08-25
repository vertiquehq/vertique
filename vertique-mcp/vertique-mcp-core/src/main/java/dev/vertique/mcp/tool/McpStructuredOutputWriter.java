// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Generated-runtime capability that writes one structured tool result through its effective JSON
 * profile mapper.
 *
 * <p>The server supplies the bounded destination and retains ownership of byte/token limits,
 * validation, observation, and terminal encoding. Application code neither implements nor calls
 * this interface.
 */
@FunctionalInterface
public interface McpStructuredOutputWriter {

    /**
     * Writes {@code value} to the caller-owned destination through the tool's stable profile mapper.
     *
     * @param value the raw application structured result
     * @param destination the server-owned bounded destination
     * @throws IOException if the mapper cannot write to the destination
     */
    void write(Object value, OutputStream destination) throws IOException;
}
