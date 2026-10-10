// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import dev.vertique.core.payload.PayloadSource;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Framework-owned view of one MCP request, passed to {@link
 * McpRequestCompletedListener#onCompleted(McpRequestCompletedEvent, McpRequestView)}.
 *
 * <p>The server binds the request's own facts as it handles the request, before any interceptor or
 * observer can alter them, and holds them for the request's lifetime. No interceptor, tool handler
 * or listener can replace what the view reports. The facts reach a listener once the request has
 * completed, so a view is complete for every path a request can take: a successful call, a
 * rejection, a tool error, a disconnect, a failed write, or an expired request deadline. A part the
 * request never reached is empty.
 *
 * <p>The view is read-only. Request and response bytes are {@link PayloadSource}s that expose no
 * write path, and every map and tree is unmodifiable. Each listener receives its own view over the
 * same underlying bytes, so one listener cannot change what another reads, and no listener can
 * change the response written to the caller. The bytes are not copied on behalf of a listener: a
 * listener that keeps a body beyond its callback copies it.
 *
 * <p>The raw request and response carry whatever the caller and the tool put there, credentials
 * included. {@code toString()} of every implementation prints no header, body or value.
 *
 * <p>The framework provides the only implementation. Applications read this interface; they do not
 * implement it outside tests.
 */
public interface McpRequestView {

    /**
     * Returns the JSON-RPC request {@code id} in its wire textual form.
     *
     * @return the id as the client sent it, or empty when the request carried none or it could not
     *     be decoded
     */
    Optional<String> jsonRpcRequestId();

    /**
     * Returns the request headers as received.
     *
     * <p>Names are lower-cased and each maps to its values in wire order. The map is an immutable
     * snapshot taken when the request was admitted. A header an interceptor adds or removes later is
     * not reflected.
     *
     * @return the headers; immutable; never {@code null}
     */
    Map<String, List<String>> requestHeaders();

    /**
     * Returns the request body, as read to decode the JSON-RPC envelope.
     *
     * @return a read-only source over the body bytes, empty when the request carried none; never
     *     {@code null}
     */
    PayloadSource requestBody();

    /**
     * Returns the response headers as they stood immediately before the terminal write.
     *
     * <p>Names are lower-cased and each maps to its values in wire order.
     *
     * @return the headers; immutable; empty when no terminal write happened; never {@code null}
     */
    Map<String, List<String>> responseHeaders();

    /**
     * Returns the response body written to the caller.
     *
     * <p>This is the bytes the single terminal writer sent: the result, a bounded error, a rejection,
     * or the bounded {@code 504} of an expired request deadline.
     *
     * @return a read-only source over the response bytes; {@link PayloadSource#kind()} is {@code
     *     ABSENT} when no terminal write happened; never {@code null}
     */
    PayloadSource responseBody();

    /**
     * Returns the pre-dispatch request snapshot and resolved tool descriptor of a {@code tools/call}
     * request that reached a prepared invocation.
     *
     * @return the invocation context, or empty when the request was rejected or failed before a tool
     *     call was prepared
     */
    Optional<McpToolInvocationContext> toolContext();

    /**
     * Returns the tool's normalized arguments, as produced after schema validation, canonicalization,
     * sanitization, materialization and Bean Validation.
     *
     * <p>The tree is deeply unmodifiable at every level. It is the value the tool was invoked with,
     * not the wire value.
     *
     * @return the normalized arguments, or empty when the request was rejected or failed before a
     *     tool call was prepared
     */
    Optional<Map<String, Object>> toolInput();

    /**
     * Returns the tool's normalized result when the framework wrote it.
     *
     * <p>Present means the result's terminal write won settlement: the value was the response body,
     * not a bounded error that replaced it. It does not mean the client received it; {@link
     * McpRequestCompletedEvent#transportOutcome()} carries that.
     *
     * @return the result, or empty when no result was written
     */
    Optional<McpToolOutput> toolOutput();
}
