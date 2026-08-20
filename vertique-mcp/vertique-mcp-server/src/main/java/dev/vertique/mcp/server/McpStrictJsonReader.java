// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

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
 * <p>The decode is framework-owned and trusts no application mapper: bytes are first validated as
 * strict UTF-8 (a JDK decoder that reports malformed sequences and lone surrogates), then parsed
 * from a streaming Jackson token source into an explicit-stack tree builder. Nesting is tracked by
 * an explicit depth counter rather than native call recursion, so an adversarial deeply-nested
 * frame is rejected at the configured bound instead of overflowing the call stack, and every limit
 * is enforced by this class rather than delegated to Jackson's own constraints (which are raised
 * above the configured bounds so this class alone classifies a rejection).
 */
final class McpStrictJsonReader {

    /** Node factory that preserves exact {@link java.math.BigDecimal} scale for lexical precision. */
    private static final JsonNodeFactory NODE_FACTORY = JsonNodeFactory.withExactBigDecimals(true);

    /**
     * Fixed internal cap on the lexical length of a numeric token (Jackson's own default). A longer
     * token is rejected before {@link java.math.BigInteger}/{@link java.math.BigDecimal}
     * materialization, defeating the O(n²) cost of parsing a multi-million-digit integer.
     */
    private static final int MAX_NUMBER_CHARS = 1000;

    /**
     * Fixed internal cap on the magnitude of a decimal's scale, aligned exactly with the encoder's
     * {@code WRITE_BIGDECIMAL_AS_PLAIN} plain-form guard. Jackson's {@code GeneratorBase} rejects a
     * plain-form encode when {@code scale < -9999 || scale > 9999}, so the reader's admitted set is the
     * encoder's safe set precisely when it rejects a scale magnitude greater than this bound — a decimal
     * whose scale magnitude is {@code 9999} is admitted and re-encodes cleanly, while {@code 10000} (in
     * either direction) is rejected. A short token such as {@code 1e999999999} passes any length bound
     * yet would otherwise decode to a {@link java.math.BigDecimal} whose plain-form encode exhausts
     * memory; bounding the scale magnitude rejects it before that.
     */
    private static final int MAX_DECIMAL_SCALE = 9_999;

    /**
     * Streaming factory whose stream-read constraints are raised above every configured MCP bound so
     * this reader — not Jackson — is the sole authority that classifies a bounded rejection.
     */
    private static final JsonFactory PARSE_FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(Integer.MAX_VALUE)
                    .maxStringLength(Integer.MAX_VALUE)
                    .maxNumberLength(Integer.MAX_VALUE)
                    .maxNameLength(Integer.MAX_VALUE)
                    .build())
            .build();

    private final int maxDepth;
    private final int maxProperties;

    private final int maxItems;

    /**
     * The string-length bound, measured in Java {@code String.length()} — i.e. UTF-16 code units, not
     * Unicode code points, so a supplementary (astral) code point counts as two toward this bound.
     */
    private final int maxStringChars;

    /**
     * Creates a reader bound to the limits carried by the supplied configuration.
     *
     * @param config the MCP server configuration whose JSON limits bound every decode
     */
    McpStrictJsonReader(McpServerConfig config) {
        this.maxDepth = config.jsonMaxDepth();
        this.maxProperties = config.jsonMaxPropertiesPerObject();
        this.maxItems = config.jsonMaxItemsPerArray();
        this.maxStringChars = config.jsonMaxStringChars();
    }

    /**
     * Strictly decodes one complete JSON value from UTF-8 bytes.
     *
     * @param utf8 the raw UTF-8 request bytes
     * @return a bounded value on success, or a classified rejection carrying no partial value
     */
    Result read(byte[] utf8) {
        if (utf8 == null) {
            return Result.rejected(Rejection.MALFORMED);
        }
        CharBuffer json;
        try {
            json = decodeStrictUtf8(utf8);
        } catch (CharacterCodingException invalidUtf8) {
            return Result.rejected(Rejection.INVALID_UTF8);
        }
        return parse(json);
    }

    // --- Strict UTF-8 boundary ---

    /**
     * Decodes bytes as strict UTF-8, reporting malformed sequences and lone surrogates.
     *
     * @param utf8 the raw request bytes
     * @return the decoded characters as an array-backed buffer
     * @throws CharacterCodingException when the bytes are not valid UTF-8
     */
    private static CharBuffer decodeStrictUtf8(byte[] utf8) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(utf8));
    }

    // --- Bounded explicit-stack parse ---

    /**
     * Parses one complete JSON value from decoded text using an explicit container stack.
     *
     * @param json the strict-UTF-8-decoded request characters
     * @return the bounded value, or a classified rejection
     */
    private Result parse(CharBuffer json) {
        try (JsonParser parser = PARSE_FACTORY.createParser(json.array(), json.arrayOffset(), json.remaining())) {
            Deque<Frame> stack = new ArrayDeque<>();
            JsonNode root = null;

            JsonToken token = parser.nextToken();
            if (token == null) {
                return Result.rejected(Rejection.MALFORMED);
            }

            while (true) {
                Frame top = stack.peek();

                if (token == JsonToken.FIELD_NAME) {
                    String name = parser.currentName();
                    if (name.length() > maxStringChars) {
                        return Result.rejected(Rejection.MAX_STRING_CHARS);
                    }
                    top.pendingField = name;
                    token = parser.nextToken();
                    continue;
                }

                if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    Frame finished = stack.pop();
                    if (stack.isEmpty()) {
                        root = finished.container;
                        break;
                    }
                    token = parser.nextToken();
                    continue;
                }

                JsonNode value;
                Frame opened = null;
                switch (token) {
                    case START_OBJECT, START_ARRAY -> {
                        if (stack.size() + 1 > maxDepth) {
                            return Result.rejected(Rejection.MAX_DEPTH);
                        }
                        JsonNode container =
                                token == JsonToken.START_OBJECT ? NODE_FACTORY.objectNode() : NODE_FACTORY.arrayNode();
                        value = container;
                        opened = new Frame(container);
                    }
                    case VALUE_STRING -> {
                        String text = parser.getText();
                        if (text.length() > maxStringChars) {
                            return Result.rejected(Rejection.MAX_STRING_CHARS);
                        }
                        value = NODE_FACTORY.textNode(text);
                    }
                    case VALUE_NUMBER_INT -> {
                        Result number = materializeNumber(parser, false);
                        if (number.isRejected()) {
                            return number;
                        }
                        value = number.value();
                    }
                    case VALUE_NUMBER_FLOAT -> {
                        Result number = materializeNumber(parser, true);
                        if (number.isRejected()) {
                            return number;
                        }
                        value = number.value();
                    }
                    case VALUE_TRUE -> value = NODE_FACTORY.booleanNode(true);
                    case VALUE_FALSE -> value = NODE_FACTORY.booleanNode(false);
                    case VALUE_NULL -> value = NODE_FACTORY.nullNode();
                    default -> {
                        return Result.rejected(Rejection.MALFORMED);
                    }
                }

                if (top != null) {
                    Rejection rejection = attach(top, value);
                    if (rejection != null) {
                        return Result.rejected(rejection);
                    }
                }
                if (opened != null) {
                    stack.push(opened);
                } else if (top == null) {
                    root = value;
                    break;
                }
                token = parser.nextToken();
            }

            try {
                if (parser.nextToken() != null) {
                    return Result.rejected(Rejection.TRAILING_TOKENS);
                }
            } catch (IOException trailing) {
                return Result.rejected(Rejection.TRAILING_TOKENS);
            }
            return Result.ok(root);
        } catch (IOException malformed) {
            return Result.rejected(Rejection.MALFORMED);
        }
    }

    /**
     * Attaches a completed value to its parent container, enforcing per-container bounds.
     *
     * @param parent the enclosing container frame
     * @param value the value to attach
     * @return a bounded rejection, or {@code null} when the value was attached within bounds
     */
    private Rejection attach(Frame parent, JsonNode value) {
        if (parent.container.isArray()) {
            parent.count++;
            if (parent.count > maxItems) {
                return Rejection.MAX_ITEMS;
            }
            ((ArrayNode) parent.container).add(value);
            return null;
        }
        ObjectNode object = (ObjectNode) parent.container;
        String name = parent.pendingField;
        if (object.has(name)) {
            return Rejection.DUPLICATE_KEY;
        }
        parent.count++;
        if (parent.count > maxProperties) {
            return Rejection.MAX_PROPERTIES;
        }
        object.set(name, value);
        parent.pendingField = null;
        return null;
    }

    /**
     * Materializes a numeric token into an exact node under fixed internal hardening bounds.
     *
     * <p>The lexical length is checked <em>before</em> any {@link java.math.BigInteger}/
     * {@link java.math.BigDecimal} materialization, so a multi-million-digit token is rejected without
     * paying the O(n²) parse cost; a decimal is materialized only once its length is in bounds, and its
     * scale magnitude is then bounded so a short token such as {@code 1e999999999} cannot drive an
     * out-of-memory plain-form encode. The materialization itself is wrapped in a narrow
     * {@link RuntimeException} catch — {@code getDecimalValue()}/{@code getBigIntegerValue()} throw an
     * unchecked {@link NumberFormatException} on exponent overflow — so such a token settles as a
     * classified {@link Rejection#MALFORMED} rather than escaping the decode uncaught.
     *
     * @param parser the streaming parser positioned on a numeric token
     * @param isFloat whether the token is a decimal (has a fraction or exponent)
     * @return the exact numeric node, or a classified numeric rejection
     * @throws IOException when the parser cannot read the token's lexical length
     */
    private static Result materializeNumber(JsonParser parser, boolean isFloat) throws IOException {
        if (parser.getTextLength() > MAX_NUMBER_CHARS) {
            return Result.rejected(Rejection.NUMBER_OUT_OF_BOUNDS);
        }
        try {
            if (isFloat) {
                BigDecimal decimal = parser.getDecimalValue();
                if (Math.abs((long) decimal.scale()) > MAX_DECIMAL_SCALE) {
                    return Result.rejected(Rejection.NUMBER_OUT_OF_BOUNDS);
                }
                return Result.ok(NODE_FACTORY.numberNode(decimal));
            }
            return Result.ok(integerNode(parser));
        } catch (RuntimeException numberOverflow) {
            return Result.rejected(Rejection.MALFORMED);
        }
    }

    /**
     * Builds an integer node that preserves the exact 64-bit or big-integer value.
     *
     * @param parser the streaming parser positioned on an integer token
     * @return the exact integer node
     * @throws IOException when the parser cannot read the number
     */
    private static JsonNode integerNode(JsonParser parser) throws IOException {
        return switch (parser.getNumberType()) {
            case INT -> NODE_FACTORY.numberNode(parser.getIntValue());
            case LONG -> NODE_FACTORY.numberNode(parser.getLongValue());
            default -> NODE_FACTORY.numberNode(parser.getBigIntegerValue());
        };
    }

    /** One open container on the parse stack: its node, item/member count, and pending object key. */
    private static final class Frame {

        private final JsonNode container;
        private int count;

        @Nullable
        private String pendingField;

        private Frame(JsonNode container) {
            this.container = container;
        }
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
        /**
         * A numeric token exceeded a fixed internal hardening bound: its lexical length exceeded
         * {@link #MAX_NUMBER_CHARS}, or a decimal's scale magnitude exceeded {@link #MAX_DECIMAL_SCALE}.
         * These bounds short-circuit before big-integer / big-decimal materialization so an adversarial
         * numeric token cannot drive an O(n²) parse or an out-of-memory plain-form encode.
         */
        NUMBER_OUT_OF_BOUNDS,
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
