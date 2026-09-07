// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.EncodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.core.json.jackson.JacksonCodec;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The process JSON codec: every databind operation runs on a swappable delegate mapper.
 *
 * <p>Vert.x selects this codec through the {@code io.vertx.core.spi.JsonFactory} service loader (see
 * {@link VertiqueJsonFactory}), so {@code Json.encode}, {@code Json.decodeValue},
 * {@code new JsonObject(String|Buffer)}, {@code JsonObject.encode()}, {@code JsonObject.mapTo} and
 * {@code JsonObject.mapFrom} all resolve here. Until {@link VertiqueJson#install} is called the
 * delegate is Vert.x's own {@link DatabindCodec#mapper()}, which makes this codec byte-identical to
 * the stock {@code DatabindCodec} for every input; afterwards the same operations run on the
 * installed profile's mapper.
 *
 * <p>Each method reproduces the {@code DatabindCodec} semantics exactly — {@code convertValue} for
 * value conversion, a parser built from the mapper's own {@code JsonFactory}, the trailing-token
 * check on the {@code Class} decode family, {@code Map}/{@code List} adaptation to
 * {@link JsonObject}/{@link JsonArray} when the requested type is {@code Object}, and the same
 * {@link DecodeException}/{@link EncodeException} messages — against the delegate rather than
 * against Vert.x's static mapper. Every operation reads the delegate <em>once</em> and uses that one
 * instance throughout, so a concurrent swap can never split a single decode between two mappers.
 *
 * <p>The delegate is only ever replaced, never reconfigured: Jackson forbids reconfiguring a mapper
 * after first use, and {@link DatabindCodec#mapper()} in particular is never mutated by the
 * framework.
 *
 * <p><strong>Not covered by the delegation:</strong> the {@code static} helpers
 * {@code DatabindCodec.createParser}/{@code fromParser} and the streaming
 * {@code JacksonCodec.fromString(String)}/{@code fromBuffer(Buffer)} overloads that return
 * {@code Object}. Those are static or non-databind entry points that Vert.x binds to its own raw
 * mapper and factory, exactly as they are for the stock codec; code that needs the installed mapper
 * calls {@link VertiqueJson#mapper()}.
 */
final class VertiqueJsonCodec extends JacksonCodec {

    /** The process-wide codec instance handed to Vert.x by {@link VertiqueJsonFactory}. */
    static final VertiqueJsonCodec INSTANCE = new VertiqueJsonCodec();

    /**
     * The installed mapper, or {@code null} while the raw Vert.x mapper is in force. Volatile: it is
     * written by the installing thread at {@code CONFIGURE} and read by every thread that encodes or
     * decodes JSON afterwards.
     */
    private volatile ObjectMapper delegate;

    /** Singleton; instances are created only by the static initializer above. */
    private VertiqueJsonCodec() {}

    // --- Delegate management (VertiqueJson is the only caller) ---

    /**
     * Returns the mapper every databind operation of this codec runs on: the installed mapper when
     * one has been installed, otherwise Vert.x's raw {@link DatabindCodec#mapper()}.
     *
     * <p>The fallback is resolved lazily on read rather than captured in the constructor: this codec
     * is instantiated while {@code Json.CODEC} is being initialized, before any Vert.x JSON class is
     * guaranteed to be initialized.
     *
     * @return the effective mapper; never {@code null}
     */
    ObjectMapper mapper() {
        ObjectMapper installed = delegate;
        return installed != null ? installed : DatabindCodec.mapper();
    }

    /**
     * Replaces the delegate mapper.
     *
     * @param mapper the mapper every subsequent databind operation runs on; must not be {@code null}
     */
    void delegateTo(ObjectMapper mapper) {
        this.delegate = mapper;
    }

    /** Restores the raw Vert.x mapper as the delegate. */
    void restoreRawDelegate() {
        this.delegate = null;
    }

    // --- JsonCodec: value conversion ---

    /**
     * Converts an already-parsed JSON value to {@code clazz} through the delegate's
     * {@code convertValue}.
     *
     * @param <T> the target type
     * @param json the source value
     * @param clazz the target type
     * @return the converted value, adapted to {@link JsonObject}/{@link JsonArray} when
     *     {@code clazz} is {@code Object}
     */
    @Override
    public <T> T fromValue(Object json, Class<T> clazz) {
        T value = mapper().convertValue(json, clazz);
        if (clazz == Object.class) {
            value = clazz.cast(adapt(value));
        }
        return value;
    }

    /**
     * Converts an already-parsed JSON value to the generic type {@code type} through the delegate's
     * {@code convertValue}.
     *
     * @param <T> the target type
     * @param json the source value
     * @param type the target type reference
     * @return the converted value, adapted to {@link JsonObject}/{@link JsonArray} when {@code type}
     *     is {@code Object}
     */
    @SuppressWarnings("unchecked")
    public <T> T fromValue(Object json, TypeReference<T> type) {
        T value = mapper().convertValue(json, type);
        if (type.getType() == Object.class) {
            value = (T) adapt(value);
        }
        return value;
    }

    // --- JsonCodec: decoding ---

    /**
     * Decodes a JSON string to {@code clazz} on the delegate.
     *
     * @param <T> the target type
     * @param json the JSON text
     * @param clazz the target type
     * @return the decoded value
     * @throws DecodeException if the text is not valid JSON, does not bind to {@code clazz}, or
     *     carries a trailing token
     */
    @Override
    public <T> T fromString(String json, Class<T> clazz) throws DecodeException {
        ObjectMapper mapper = mapper();
        return fromParser(mapper, createParser(mapper, json), clazz);
    }

    /**
     * Decodes a JSON string to the generic type {@code type} on the delegate.
     *
     * @param <T> the target type
     * @param json the JSON text
     * @param type the target type reference
     * @return the decoded value
     * @throws DecodeException if the text is not valid JSON or does not bind to {@code type}
     */
    public <T> T fromString(String json, TypeReference<T> type) throws DecodeException {
        ObjectMapper mapper = mapper();
        return fromParser(mapper, createParser(mapper, json), type);
    }

    /**
     * Decodes a JSON buffer to {@code clazz} on the delegate.
     *
     * @param <T> the target type
     * @param json the JSON bytes
     * @param clazz the target type
     * @return the decoded value
     * @throws DecodeException if the bytes are not valid JSON, do not bind to {@code clazz}, or
     *     carry a trailing token
     */
    @Override
    public <T> T fromBuffer(Buffer json, Class<T> clazz) throws DecodeException {
        ObjectMapper mapper = mapper();
        return fromParser(mapper, createParser(mapper, json), clazz);
    }

    /**
     * Decodes a JSON buffer to the generic type {@code type} on the delegate.
     *
     * @param <T> the target type
     * @param json the JSON bytes
     * @param type the target type reference
     * @return the decoded value
     * @throws DecodeException if the bytes are not valid JSON or do not bind to {@code type}
     */
    public <T> T fromBuffer(Buffer json, TypeReference<T> type) throws DecodeException {
        ObjectMapper mapper = mapper();
        return fromParser(mapper, createParser(mapper, json), type);
    }

    // --- JsonCodec: encoding ---

    /**
     * Encodes a value as JSON text on the delegate.
     *
     * @param object the value to encode
     * @param pretty whether to use the default pretty printer
     * @return the JSON text
     * @throws EncodeException if the value cannot be encoded
     */
    @Override
    public String toString(Object object, boolean pretty) throws EncodeException {
        try {
            ObjectMapper mapper = mapper();
            return pretty
                    ? mapper.writerWithDefaultPrettyPrinter().writeValueAsString(object)
                    : mapper.writeValueAsString(object);
        } catch (Exception e) {
            throw new EncodeException("Failed to encode as JSON: " + e.getMessage());
        }
    }

    /**
     * Encodes a value as JSON bytes on the delegate.
     *
     * @param object the value to encode
     * @param pretty whether to use the default pretty printer
     * @return a buffer holding the JSON bytes
     * @throws EncodeException if the value cannot be encoded
     */
    @Override
    public Buffer toBuffer(Object object, boolean pretty) throws EncodeException {
        try {
            ObjectMapper mapper = mapper();
            byte[] bytes = pretty
                    ? mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(object)
                    : mapper.writeValueAsBytes(object);
            return Buffer.buffer(bytes);
        } catch (Exception e) {
            throw new EncodeException("Failed to encode as JSON: " + e.getMessage());
        }
    }

    // --- Internals ---

    /**
     * Creates a parser for JSON text from {@code mapper}'s own factory, so the installed profile's
     * parser features and stream-read constraints govern the read.
     *
     * @param mapper the mapper this operation runs on
     * @param json the JSON text
     * @return an open parser positioned before the first token
     * @throws DecodeException if the parser cannot be created
     */
    private static JsonParser createParser(ObjectMapper mapper, String json) {
        try {
            return mapper.getFactory().createParser(json);
        } catch (IOException e) {
            throw new DecodeException("Failed to decode:" + e.getMessage(), e);
        }
    }

    /**
     * Creates a parser for JSON bytes from {@code mapper}'s own factory.
     *
     * <p>The bytes are read out of the buffer rather than wrapped zero-copy, because the zero-copy
     * route Vert.x uses is bound to its raw {@code JsonFactory}. Binding the buffer family to the
     * installed mapper's factory instead is what keeps a buffer decode and a string decode equally
     * strict: parser-level leniency (JSON comments, for example) and the operator's stream-read
     * limits belong to the factory, so a profile that rejects a construct in text must reject it in
     * bytes too.
     *
     * @param mapper the mapper this operation runs on
     * @param json the JSON bytes
     * @return an open parser positioned before the first token
     * @throws DecodeException if the parser cannot be created
     */
    private static JsonParser createParser(ObjectMapper mapper, Buffer json) {
        try {
            return mapper.getFactory().createParser(json.getBytes());
        } catch (IOException e) {
            throw new DecodeException("Failed to decode:" + e.getMessage(), e);
        }
    }

    /**
     * Binds a parser's content to {@code clazz}, rejecting a trailing token and adapting a generic
     * result to the Vert.x JSON types.
     *
     * @param <T> the target type
     * @param mapper the mapper this operation runs on
     * @param parser the parser to read from; always closed
     * @param clazz the target type
     * @return the decoded value
     * @throws DecodeException if binding fails or a token follows the decoded value
     */
    private static <T> T fromParser(ObjectMapper mapper, JsonParser parser, Class<T> clazz) throws DecodeException {
        T value;
        JsonToken remaining;
        try {
            value = mapper.readValue(parser, clazz);
            remaining = parser.nextToken();
        } catch (Exception e) {
            throw new DecodeException("Failed to decode:" + e.getMessage(), e);
        } finally {
            close(parser);
        }
        if (remaining != null) {
            throw new DecodeException("Unexpected trailing token");
        }
        if (clazz == Object.class) {
            value = clazz.cast(adapt(value));
        }
        return value;
    }

    /**
     * Binds a parser's content to the generic type {@code type}, adapting a generic result to the
     * Vert.x JSON types.
     *
     * <p>Unlike the {@code Class} family this performs no trailing-token check — the same asymmetry
     * the stock Vert.x databind codec has.
     *
     * @param <T> the target type
     * @param mapper the mapper this operation runs on
     * @param parser the parser to read from; always closed
     * @param type the target type reference
     * @return the decoded value
     * @throws DecodeException if binding fails
     */
    @SuppressWarnings("unchecked")
    private static <T> T fromParser(ObjectMapper mapper, JsonParser parser, TypeReference<T> type)
            throws DecodeException {
        T value;
        try {
            value = mapper.readValue(parser, type);
        } catch (Exception e) {
            throw new DecodeException("Failed to decode:" + e.getMessage(), e);
        } finally {
            close(parser);
        }
        if (type.getType() == Object.class) {
            value = (T) adapt(value);
        }
        return value;
    }

    /**
     * Adapts a generically decoded {@code Map}/{@code List} to the Vert.x JSON types so an
     * {@code Object}-typed decode yields {@link JsonObject}/{@link JsonArray}.
     *
     * @param value the decoded value
     * @return the adapted value, or {@code value} itself when it is neither a map nor a list
     * @throws DecodeException if adaptation fails
     */
    @SuppressWarnings("unchecked")
    private static Object adapt(Object value) {
        try {
            if (value instanceof List<?> list) {
                return new JsonArray(list);
            }
            if (value instanceof Map<?, ?> map) {
                return new JsonObject((Map<String, Object>) map);
            }
            return value;
        } catch (Exception e) {
            throw new DecodeException("Failed to decode: " + e.getMessage());
        }
    }

    /**
     * Closes a parser, ignoring a close failure exactly as Vert.x does.
     *
     * @param closeable the parser to close
     */
    private static void close(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // A failure to close a fully-read parser carries no information for the caller.
        }
    }
}
