// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.vertique.core.json.JsonProfileCodec;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.ProcessJsonCodec;
import dev.vertique.core.json.VertiqueJson;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.EncodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.core.json.jackson.JacksonCodec;
import io.vertx.core.json.jackson.VertxModule;
import jakarta.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The process JSON codec: every databind operation runs on a swappable delegate mapper.
 *
 * <p>Vert.x selects this codec through the {@code io.vertx.core.spi.JsonFactory} service loader (see
 * {@link VertiqueJsonFactory}), so {@code Json.encode}, {@code Json.decodeValue},
 * {@code new JsonObject(String|Buffer)}, {@code JsonObject.encode()}, {@code JsonObject.mapTo} and
 * {@code JsonObject.mapFrom} and the stream, reader and writer forms ({@code fromStream},
 * {@code fromReader}, {@code toStream}, {@code toWriter}) all resolve here. Until {@link VertiqueJson#install} is called the
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
 * calls {@code Jackson2JsonProfileCodec.objectMapper(VertiqueJson.codec())}.
 */
final class ProfileDelegatingJsonCodec extends JacksonCodec implements ProcessJsonCodec {

    /** The process-wide codec instance handed to Vert.x by {@link VertiqueJsonFactory}. */
    static final ProfileDelegatingJsonCodec INSTANCE = new ProfileDelegatingJsonCodec();

    /** The raw profile's id: the codec runs Vert.x's own mapper under it until a profile is installed. */
    private static final JsonProfileId RAW_ID = JsonProfileId.of("vertx");

    /**
     * The pre-install profile over Vert.x's raw mapper. It resolves {@link DatabindCodec#mapper()} on first
     * use, not here: this codec is created while {@code Json.CODEC} is being initialized, before any Vert.x
     * JSON class is guaranteed to be initialized.
     */
    private final JsonProfileCodec raw = new Jackson2JsonProfileCodec() {
        @Override
        public JsonProfileId id() {
            return RAW_ID;
        }

        @Override
        protected ObjectMapper mapper() {
            return DatabindCodec.mapper();
        }
    };

    /**
     * The installed profile and its guarded mapper, published together, or {@code null} while the raw
     * profile is in force. Volatile: written by the installing thread at {@code CONFIGURE} and read by every
     * thread that encodes or decodes JSON afterwards.
     */
    private volatile Installed installed;

    /** Singleton; instances are created only by the static initializer above. */
    private ProfileDelegatingJsonCodec() {}

    // --- ProcessJsonCodec (VertiqueJson is the only caller) ---

    @Override
    public JsonProfileCodec raw() {
        return raw;
    }

    @Override
    public JsonProfileCodec current() {
        Installed current = installed;
        return current != null ? current.profile() : raw;
    }

    @Override
    public ProcessJsonCodec.Prepared validate(JsonProfileCodec profile, @Nullable JsonProfileCodec installedProfile) {
        JsonProfileId id = profile.id();
        if (!(profile instanceof Jackson2JsonProfileCodec)) {
            throw new IllegalArgumentException("JSON profile '" + id.value()
                    + "' cannot be the process JSON codec's profile: it is not a Jackson2JsonProfileCodec");
        }
        ObjectMapper mapper = Jackson2JsonProfileCodec.objectMapper(profile);
        requireVertxModule(id, mapper);
        requireNoDefaultTyping(id, mapper);
        List<String> widened = installedProfile == null
                ? List.of()
                : leniencyWidening(Jackson2JsonProfileCodec.objectMapper(installedProfile), mapper);
        return new Prepared(profile, mapper, widened);
    }

    @Override
    public void swap(ProcessJsonCodec.Prepared prepared) {
        if (!(prepared instanceof Prepared ours)) {
            throw new IllegalArgumentException("the prepared install was not produced by this codec");
        }
        this.installed = new Installed(ours.profile(), ours.mapper());
    }

    @Override
    public void reset() {
        this.installed = null;
    }

    /**
     * Returns the mapper every databind operation of this codec runs on: the installed profile's guarded
     * mapper, otherwise Vert.x's raw {@link DatabindCodec#mapper()}, resolved at read time.
     *
     * @return the effective mapper; never {@code null}
     */
    ObjectMapper mapper() {
        Installed current = installed;
        return current != null ? current.mapper() : DatabindCodec.mapper();
    }

    /** An installed profile and the mapper the guards ran on. */
    private record Installed(JsonProfileCodec profile, ObjectMapper mapper) {}

    /** This codec's validated install: the profile, its guarded mapper and the widened settings. */
    private record Prepared(JsonProfileCodec profile, ObjectMapper mapper, List<String> widenedSettings)
            implements ProcessJsonCodec.Prepared {}

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

    // --- JsonCodec: streams, readers and writers ---

    /**
     * Decodes one JSON value from {@code stream} into {@code clazz} on the delegate, as
     * {@code DatabindCodec.fromStream} does on Vert.x's mapper: the parser comes from the delegate's
     * own {@code JsonFactory}, never closes the caller's stream, and the trailing-token check applies.
     * The one-argument {@code fromStream(InputStream)} reaches this method with {@code Object.class}.
     *
     * @param <T> the target type
     * @param stream the JSON input; left open
     * @param clazz the target type
     * @return the decoded value
     * @throws DecodeException if the input is malformed, has trailing content or cannot be bound
     */
    @Override
    public <T> T fromStream(InputStream stream, Class<T> clazz) throws DecodeException {
        ObjectMapper mapper = mapper();
        try (JsonParser parser =
                mapper.getFactory().createParser(stream).disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)) {
            return fromParser(mapper, parser, clazz);
        } catch (IOException e) {
            throw new DecodeException("Failed to decode:" + e.getMessage(), e);
        }
    }

    /**
     * Decodes one JSON value from {@code reader} into {@code clazz} on the delegate, with the same
     * parser rules as {@link #fromStream(InputStream, Class)}. The one-argument
     * {@code fromReader(Reader)} reaches this method with {@code Object.class}.
     *
     * @param <T> the target type
     * @param reader the JSON input; left open
     * @param clazz the target type
     * @return the decoded value
     * @throws DecodeException if the input is malformed, has trailing content or cannot be bound
     */
    @Override
    public <T> T fromReader(Reader reader, Class<T> clazz) throws DecodeException {
        ObjectMapper mapper = mapper();
        try (JsonParser parser =
                mapper.getFactory().createParser(reader).disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)) {
            return fromParser(mapper, parser, clazz);
        } catch (IOException e) {
            throw new DecodeException("Failed to decode:" + e.getMessage(), e);
        }
    }

    /**
     * Writes {@code object} as JSON to {@code stream} on the delegate, as {@code DatabindCodec.toStream}
     * does on Vert.x's mapper: the caller's stream is neither closed nor flushed.
     *
     * @param object the value to encode
     * @param stream the target; left open and unflushed
     * @throws EncodeException if encoding fails
     */
    @Override
    public void toStream(Object object, OutputStream stream) throws EncodeException {
        try {
            streamWriter(mapper()).writeValue(stream, object);
        } catch (Exception e) {
            throw new EncodeException("Failed to encode as JSON: " + e.getMessage());
        }
    }

    /**
     * Writes {@code object} as JSON to {@code writer} on the delegate, with the same rules as
     * {@link #toStream(Object, OutputStream)}.
     *
     * @param object the value to encode
     * @param writer the target; left open and unflushed
     * @throws EncodeException if encoding fails
     */
    @Override
    public void toWriter(Object object, Writer writer) throws EncodeException {
        try {
            streamWriter(mapper()).writeValue(writer, object);
        } catch (Exception e) {
            throw new EncodeException("Failed to encode as JSON: " + e.getMessage());
        }
    }

    // --- Guards ---

    /**
     * Rejects a mapper that does not carry Vert.x's Jackson module.
     *
     * @param id the profile id being installed, for the failure message
     * @param mapper the candidate mapper
     * @throws IllegalArgumentException if the module is not registered
     */
    private static void requireVertxModule(JsonProfileId id, ObjectMapper mapper) {
        if (!mapper.getRegisteredModuleIds().contains(VertxModule.class.getName())) {
            throw new IllegalArgumentException("the mapper of JSON profile '" + id.value()
                    + "' cannot be the process JSON codec's mapper: it does not register Vert.x's Jackson module, so"
                    + " JsonObject, JsonArray and Buffer values would be bean-serialized instead of round-tripping");
        }
    }

    /**
     * Rejects a mapper with Jackson default typing active.
     *
     * @param id the profile id being installed, for the failure message
     * @param mapper the candidate mapper
     * @throws IllegalArgumentException if default typing is active
     */
    private static void requireNoDefaultTyping(JsonProfileId id, ObjectMapper mapper) {
        if (mapper.getDeserializationConfig().getDefaultTyper(null) != null) {
            throw new IllegalArgumentException("the mapper of JSON profile '" + id.value()
                    + "' cannot be the process JSON codec's mapper: it activates Jackson default typing, which lets"
                    + " any payload reaching the codec choose the type it deserializes into. Annotation-driven"
                    + " polymorphism is unaffected");
        }
    }

    /**
     * Parser features that widen what the process codec accepts as JSON text; a same-id swap that
     * turns any of them on is reported, mirroring the CONFIGURE-time install step's own list.
     */
    private static final List<JsonParser.Feature> LENIENT_PARSER_FEATURES = List.of(
            JsonParser.Feature.ALLOW_COMMENTS,
            JsonParser.Feature.ALLOW_SINGLE_QUOTES,
            JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES,
            JsonParser.Feature.ALLOW_TRAILING_COMMA,
            JsonParser.Feature.ALLOW_MISSING_VALUES,
            JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER,
            JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS,
            JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS,
            JsonParser.Feature.ALLOW_YAML_COMMENTS);

    /**
     * Returns every read-leniency setting the incoming mapper widens relative to the mapper it replaces,
     * which {@code VertiqueJson} logs at {@code WARN} on a same-id swap.
     *
     * <p>A same-id swap is permitted because code running inside the process is trusted, but it is
     * never silent: the two structural invariants ({@link #requireVertxModule},
     * {@link #requireNoDefaultTyping}) are checked on every install regardless, and this comparison
     * covers what those invariants do not — read leniency that widens what every payload the process
     * codec decodes afterwards is accepted as.
     *
     * @param previous the mapper being replaced
     * @param incoming the mapper taking its place
     * @return the widened settings, human-readable; empty when nothing widens
     */
    private static List<String> leniencyWidening(ObjectMapper previous, ObjectMapper incoming) {
        List<String> deltas = new ArrayList<>();
        addWhenNoLongerFails(
                deltas,
                "FAIL_ON_UNKNOWN_PROPERTIES",
                previous.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
                incoming.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        addWhenNoLongerFails(
                deltas,
                "FAIL_ON_TRAILING_TOKENS",
                previous.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS),
                incoming.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
        addWhenNowAccepted(
                deltas,
                "ACCEPT_CASE_INSENSITIVE_PROPERTIES",
                previous.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES),
                incoming.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES));
        for (JsonParser.Feature lenient : LENIENT_PARSER_FEATURES) {
            addWhenNowAccepted(deltas, lenient.name(), previous.isEnabled(lenient), incoming.isEnabled(lenient));
        }
        deltas.addAll(weakerReadLimits(
                previous.getFactory().streamReadConstraints(),
                incoming.getFactory().streamReadConstraints()));
        return deltas;
    }

    /**
     * Records a delta when a feature that used to make decoding fail no longer does.
     *
     * @param deltas the accumulating descriptions
     * @param feature the feature's name, for the message
     * @param previousFails whether the replaced mapper had the feature enabled (failing)
     * @param incomingFails whether the incoming mapper has the feature enabled (failing)
     */
    private static void addWhenNoLongerFails(
            List<String> deltas, String feature, boolean previousFails, boolean incomingFails) {
        if (previousFails && !incomingFails) {
            deltas.add(feature + " is off (was on)");
        }
    }

    /**
     * Records a delta when a feature that widens what decoding accepts turns on.
     *
     * @param deltas the accumulating descriptions
     * @param feature the feature's name, for the message
     * @param previousAccepts whether the replaced mapper had the feature enabled (accepting)
     * @param incomingAccepts whether the incoming mapper has the feature enabled (accepting)
     */
    private static void addWhenNowAccepted(
            List<String> deltas, String feature, boolean previousAccepts, boolean incomingAccepts) {
        if (!previousAccepts && incomingAccepts) {
            deltas.add(feature + " is on (was off)");
        }
    }

    /**
     * Returns a description of every stream-read limit the incoming mapper's factory weakens
     * relative to the mapper it replaces.
     *
     * @param previous the replaced mapper's factory limits
     * @param incoming the incoming mapper's factory limits
     * @return one entry per weakened limit; empty when none is weakened
     */
    private static List<String> weakerReadLimits(StreamReadConstraints previous, StreamReadConstraints incoming) {
        List<String> weakened = new ArrayList<>();
        // maxNestingDepth/maxNumberLength/maxStringLength/maxNameLength are always positive in
        // Jackson (the builder rejects non-positive values), so only "higher" is weaker for them;
        // maxDocumentLength and maxTokenCount treat a non-positive value as unbounded.
        addWhenHigher(weakened, "maxNestingDepth", incoming.getMaxNestingDepth(), previous.getMaxNestingDepth());
        addWhenHigher(weakened, "maxNumberLength", incoming.getMaxNumberLength(), previous.getMaxNumberLength());
        addWhenHigher(weakened, "maxStringLength", incoming.getMaxStringLength(), previous.getMaxStringLength());
        addWhenHigher(weakened, "maxNameLength", incoming.getMaxNameLength(), previous.getMaxNameLength());
        addWhenWeaker(weakened, "maxDocumentLength", incoming.getMaxDocumentLength(), previous.getMaxDocumentLength());
        addWhenWeaker(weakened, "maxTokenCount", incoming.getMaxTokenCount(), previous.getMaxTokenCount());
        return weakened;
    }

    /**
     * Records a stream-read limit that is higher than, or unbounded relative to, the mapper being
     * replaced.
     *
     * @param weakened the accumulating descriptions
     * @param limit the limit's name
     * @param incoming the incoming mapper's value
     * @param previous the replaced mapper's value
     */
    private static void addWhenHigher(List<String> weakened, String limit, long incoming, long previous) {
        if (incoming > previous) {
            weakened.add("the stream-read limit " + limit + " (" + incoming + ") is weaker than the previous mapper's ("
                    + previous + ")");
        }
    }

    /**
     * Records {@code limit} when the incoming read constraint is looser than the previous one: a larger
     * bound, or the unbounded sentinel replacing a bound.
     */
    private static void addWhenWeaker(List<String> weakened, String limit, long incoming, long previous) {
        boolean previousIsBounded = previous > 0;
        boolean incomingIsUnbounded = incoming <= 0;
        if (previousIsBounded && (incomingIsUnbounded || incoming > previous)) {
            weakened.add("the stream-read limit " + limit + " (" + incoming + ") is weaker than the previous mapper's ("
                    + previous + ")");
        }
    }

    // --- Internals ---

    /**
     * Returns a writer on {@code mapper} that neither closes nor flushes the caller's target.
     *
     * @param mapper the mapper to write with
     * @return the configured writer
     */
    private static ObjectWriter streamWriter(ObjectMapper mapper) {
        return mapper.writer()
                .without(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                .without(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM);
    }

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
