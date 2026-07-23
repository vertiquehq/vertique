// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.parser;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vertique.json.keyed.KeyedCollectionModule;
import io.vertx.core.json.jackson.VertxModule;
import java.util.List;

/**
 * Factory for the immutable, isolated config {@link ObjectMapper} the config-parsing seam uses.
 *
 * <p>{@link #lenient()} builds a <em>fresh</em> mapper that never shares state with Vert.x's
 * {@code DatabindCodec.mapper()} or any global mapper. It registers the four config modules —
 * {@link Jdk8Module}, {@link JavaTimeModule}, {@link KeyedCollectionModule}, and Vert.x's
 * {@link VertxModule} — enables lenient scalar coercion, and disables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} (extra keys ignored). This is the byte-for-byte default the
 * config parser has always used.
 *
 * <p>{@link #finalizeForConfig(ObjectMapper)} takes an application-supplied {@code @ConfigMapper}
 * override and re-layers the framework's mandatory modules and lenient policy over it <em>in
 * place</em> (no copy), so a custom mapper never loses the framework's required config behavior.
 *
 * <p>Vert.x's {@link VertxModule} is registered (the same Jackson module
 * {@code DatabindCodec.mapper()} installs) so config record fields typed as
 * {@link io.vertx.core.json.JsonObject}/{@link io.vertx.core.json.JsonArray} — and
 * {@link java.time.Instant}/{@code byte[]} — round-trip with their values intact. Without it the
 * isolated mapper has no (de)serializer for these Vert.x types and would silently bind such a field
 * to an <em>empty</em> object.
 */
public final class DefaultConfigMapper {

    private DefaultConfigMapper() {}

    /**
     * Builds the lenient config {@link ObjectMapper} — the byte-for-byte default the config parser
     * has always used: lenient scalar coercion, {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, with the
     * four config modules registered.
     *
     * @return a freshly constructed, isolated lenient config mapper
     */
    public static ObjectMapper lenient() {
        return JsonMapper.builder()
                .enable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                // The four mandatory config modules, VertxModule last (see mandatoryModules): its
                // serializers/deserializers for JsonObject/JsonArray (and Instant/byte[]) round-trip
                // those Vert.x types intact rather than binding to an empty object.
                .addModules(mandatoryModules())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Re-layers the framework's mandatory config configuration over an application-supplied override
     * mapper <em>in place</em> and returns the <strong>same instance</strong> (no copy — {@code copy()}
     * throws on {@link JsonMapper} subclasses).
     *
     * <p>Applied, all of which always win over the override:
     * <ul>
     *   <li>the four {@link #mandatoryModules() mandatory modules} are re-layered — duplicate
     *       registrations are skipped by Jackson via {@code getTypeId()}, so an override that already
     *       registered any of these keeps its instance and the rest are layered in;</li>
     *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} disabled;</li>
     *   <li>lenient scalar coercion — a string-encoded scalar coerces to its target type — via the
     *       modern {@code coercionConfigDefaults()} API (the deprecated
     *       {@code configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, …)} path is not used here).</li>
     * </ul>
     *
     * <p>The override may add app-specific modules/serializers; it cannot strip or weaken the
     * framework's mandatory modules or lenient policy — an override that sets a <em>conflicting</em>
     * feature (e.g. {@code FAIL_ON_UNKNOWN_PROPERTIES=true} to reject unknown config keys) has it
     * reversed in place here. Because this mutates the mapper before first use, the
     * {@code @ConfigMapper} binding must be a <strong>dedicated</strong> instance — not one shared with
     * other (e.g. request-body) parsing, since these mutations would bleed into that use.
     *
     * @param mapper the application-supplied override mapper to finalize; must not be {@code null}
     * @return the same {@code mapper} instance, finalized for config parsing
     */
    public static ObjectMapper finalizeForConfig(ObjectMapper mapper) {
        mapper.registerModules(mandatoryModules());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.coercionConfigDefaults().setCoercion(CoercionInputShape.String, CoercionAction.TryConvert);
        return mapper;
    }

    /**
     * Builds a fresh instance of each of the four mandatory config modules, in canonical registration
     * order with {@link VertxModule} last. This is the single source of the mandatory-module list so
     * {@link #lenient()} and {@link #finalizeForConfig(ObjectMapper)} cannot drift apart.
     *
     * <p>Fresh instances are returned on every call because a Jackson module must not be shared across
     * the distinct mappers these two methods build. {@link VertxModule} is ordered last so its
     * (de)serializers for the Vert.x JSON types win on the isolated lenient mapper; among these four
     * distinct, disjoint-type modules the relative order is otherwise immaterial.
     *
     * @return a list of the four mandatory modules, each a fresh instance, in order: {@link Jdk8Module},
     *     {@link JavaTimeModule}, {@link KeyedCollectionModule}, then {@link VertxModule}
     */
    private static List<Module> mandatoryModules() {
        return List.of(new Jdk8Module(), new JavaTimeModule(), new KeyedCollectionModule(), new VertxModule());
    }
}
