// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.rest.core.config.HttpConfig;
import java.nio.charset.StandardCharsets;

/**
 * The synthetic sensitivity probe for {@code McpBoundedWritePathArchitectureTest} (R14 item 4): one
 * class, outside {@link McpProtocolCodec} itself, that calls each of the three surviving
 * byte-unbounded codec error helpers exactly once.
 *
 * <p>Lives in {@code dev.vertique.mcp.server} because those helpers are package-private — a probe in
 * the architecture test's own package could not call them, and a rule proven only against production
 * classes that all pass is a rule that has never been shown to be able to fail. It is never executed:
 * the architecture test imports its <em>compiled bytecode</em> and evaluates the same rule against it,
 * expecting exactly three violations.
 *
 * <p>Public only so the architecture test in the sibling {@code .architecture} package can name the
 * class; it is test scope, never packaged.
 */
public final class McpUnboundedCodecCallProbe {

    private McpUnboundedCodecCallProbe() {}

    /** Calls all three byte-unbounded helpers, standing in for a regressed production write path. */
    static void callEveryUnboundedHelper() {
        McpProtocolCodec codec = new McpProtocolCodec(
                HttpConfig.builder().build(), McpServerConfig.defaults().ingressMaxTokens());
        byte[] frame = "{}".getBytes(StandardCharsets.UTF_8);
        codec.errorResponse(frame);
        McpProtocolCodec.Decoded decoded = codec.decodeEnvelope(frame);
        if (decoded.isError()) {
            codec.errorResponseFor(decoded);
        }
        codec.internalFallback(null, new IllegalStateException("probe"));
    }
}
