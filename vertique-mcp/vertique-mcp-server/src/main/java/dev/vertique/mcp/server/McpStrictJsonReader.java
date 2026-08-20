// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.annotation.Nullable;

/**
 * Strict, bounded UTF-8 JSON reader for the MCP wire layer.
 *
 * <p>The reader decodes exactly one complete JSON value and rejects — with a bounded, classified
 * {@link Rejection} and no partial value — any input that carries duplicate object keys, trailing
 * tokens after a complete value, nesting deeper than {@link McpServerConfig#jsonMaxDepth()}, an
 * object with more members than {@link McpServerConfig#jsonMaxPropertiesPerObject()}, an array with
 * more items than {@link McpServerConfig#jsonMaxItemsPerArray()}, a string longer than
 * {@link McpServerConfig#jsonMaxStringChars()}, or invalid UTF-8 (including lone surrogates). It
 * preserves integer and decimal lexical precision so downstream schema validation sees the exact
 * value the client sent.
 *
 * <p>This class is a non-functional skeleton: the T003 implementation slice replaces
 * {@link #read(byte[])} with the strict-decode logic. The current body exists only so the wire layer
 * compiles and the T003 proofs fail on their decisive assertions rather than on setup errors.
 */
final class McpStrictJsonReader {

    private final McpServerConfig config;

    /**
     * Creates a reader bound to the limits carried by the supplied configuration.
     *
     * @param config the MCP server configuration whose JSON limits bound every decode
     */
    McpStrictJsonReader(McpServerConfig config) {
        this.config = config;
    }

    /**
     * Strictly decodes one complete JSON value from UTF-8 bytes.
     *
     * @param utf8 the raw UTF-8 request bytes
     * @return a bounded value on success, or a classified rejection carrying no partial value
     */
    Result read(byte[] utf8) {
        // Skeleton: the implementation slice enforces every limit from config. The stub records the
        // config/input dependency and returns a placeholder value so the proofs reach their
        // decisive assertions instead of erroring in setup.
        if (utf8 == null || config.jsonMaxDepth() < 0) {
            return Result.rejected(Rejection.MALFORMED);
        }
        return Result.ok(NullNode.getInstance());
    }

    /** Bounded classification for a strict-decode rejection. */
    enum Rejection {
        /** An object declared the same member key more than once. */
        DUPLICATE_KEY,
        /** One or more tokens followed a complete top-level value. */
        TRAILING_TOKENS,
        /** Nesting exceeded {@link McpServerConfig#jsonMaxDepth()}. */
        MAX_DEPTH,
        /** An object exceeded {@link McpServerConfig#jsonMaxPropertiesPerObject()} members. */
        MAX_PROPERTIES,
        /** An array exceeded {@link McpServerConfig#jsonMaxItemsPerArray()} items. */
        MAX_ITEMS,
        /** A string exceeded {@link McpServerConfig#jsonMaxStringChars()} characters. */
        MAX_STRING_CHARS,
        /** The bytes were not valid UTF-8, or contained a lone surrogate. */
        INVALID_UTF8,
        /** The bytes were not a single well-formed JSON value. */
        MALFORMED
    }

    /**
     * The outcome of a strict decode: either a bounded {@code value} or a classified
     * {@code rejection}, never both and never a partial value.
     *
     * @param value the decoded JSON value, or {@code null} when the decode was rejected
     * @param rejection the bounded rejection classification, or {@code null} on success
     */
    record Result(@Nullable JsonNode value, @Nullable Rejection rejection) {

        /**
         * Reports whether the decode was rejected.
         *
         * @return {@code true} when a bounded rejection was produced
         */
        boolean isRejected() {
            return rejection != null;
        }

        /**
         * Wraps a successfully decoded value.
         *
         * @param value the decoded JSON value
         * @return a successful result
         */
        static Result ok(JsonNode value) {
            return new Result(value, null);
        }

        /**
         * Wraps a bounded rejection.
         *
         * @param rejection the rejection classification
         * @return a rejected result carrying no value
         */
        static Result rejected(Rejection rejection) {
            return new Result(null, rejection);
        }
    }
}
