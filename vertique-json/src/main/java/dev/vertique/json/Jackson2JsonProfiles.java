// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonProfileCodec;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Canonical factory for creating {@link JsonProfileCodec} instances from an id and a
 * pre-configured Jackson {@link ObjectMapper}, optionally declaring JSON Schema type overrides.
 *
 * <p>The factory validates that its arguments are non-null and pairs them into a profile that
 * exposes the given mapper instance <strong>directly</strong> — it never copies, wraps, or mutates
 * the supplied mapper. Whatever configuration the caller installed on the mapper is
 * the configuration the profile exposes.
 */
public final class Jackson2JsonProfiles {

    private Jackson2JsonProfiles() {}

    /**
     * Creates a {@link JsonProfileCodec} pairing {@code id} with {@code mapper} and declaring no
     * JSON Schema type overrides.
     *
     * <p>The returned profile's {@code unwrap(ObjectMapper.class)} returns the exact
     * {@code ObjectMapper} instance passed in — no defensive copy is made, and the mapper is not
     * mutated. This is the pre-amendment factory shape; its behavior is unchanged by
     * the addition of {@link #of(JsonProfileId, ObjectMapper, Collection)}.
     *
     * @param id the non-null profile id the returned profile reports from {@link JsonProfileCodec#id()}
     * @param mapper the non-null, pre-configured mapper the returned profile exposes as-is
     * @return a non-null {@link JsonProfileCodec} backed by the given id and mapper, declaring no
     *     schema overrides
     * @throws NullPointerException if {@code id} or {@code mapper} is {@code null}
     */
    public static Jackson2JsonProfileCodec of(JsonProfileId id, ObjectMapper mapper) {
        Objects.requireNonNull(id, "JsonProfileCodec id must not be null");
        Objects.requireNonNull(mapper, "JsonProfileCodec mapper must not be null");
        return new RecordedProfile(id, mapper, List.of());
    }

    /**
     * Creates a {@link JsonProfileCodec} pairing {@code id} with {@code mapper}, additionally
     * declaring {@code overrides} as the profile's JSON Schema type overrides.
     *
     * <p>{@code overrides} is defensively copied into an immutable list: the caller's collection may
     * be freely mutated after this call returns without affecting the returned profile, and the
     * returned profile's {@link JsonProfileCodec#jsonSchemaTypeOverrides()} is unmodifiable and
     * returns the same list instance on every call.
     *
     * @param id the non-null profile id the returned profile reports from {@link JsonProfileCodec#id()}
     * @param mapper the non-null, pre-configured mapper the returned profile exposes as-is
     * @param overrides the non-null collection of overrides to declare; must not contain a
     *     {@code null} element
     * @return a non-null {@link JsonProfileCodec} backed by the given id and mapper, declaring an
     *     immutable copy of {@code overrides}
     * @throws NullPointerException if {@code id}, {@code mapper}, {@code overrides}, or any element
     *     of {@code overrides} is {@code null}
     */
    public static Jackson2JsonProfileCodec of(
            JsonProfileId id, ObjectMapper mapper, Collection<JsonSchemaTypeOverride> overrides) {
        Objects.requireNonNull(id, "JsonProfileCodec id must not be null");
        Objects.requireNonNull(mapper, "JsonProfileCodec mapper must not be null");
        Objects.requireNonNull(overrides, "JsonProfileCodec overrides must not be null");
        List<JsonSchemaTypeOverride> copy = List.copyOf(overrides);
        return new RecordedProfile(id, mapper, copy);
    }

    /**
     * The profile {@link #of} returns: an id, a mapper exposed as-is, and a fixed override list. Two
     * instances are equal when their ids and override lists are equal and they wrap the same mapper
     * instance, so equal contributions still deduplicate in a {@code Set}.
     */
    private static final class RecordedProfile extends Jackson2JsonProfileCodec {
        private final JsonProfileId id;
        private final ObjectMapper mapper;
        private final List<JsonSchemaTypeOverride> overrides;

        RecordedProfile(JsonProfileId id, ObjectMapper mapper, List<JsonSchemaTypeOverride> overrides) {
            this.id = id;
            this.mapper = mapper;
            this.overrides = overrides;
        }

        @Override
        public JsonProfileId id() {
            return id;
        }

        @Override
        protected ObjectMapper mapper() {
            return mapper;
        }

        @Override
        public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
            return overrides;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RecordedProfile that
                    && id.equals(that.id)
                    && mapper == that.mapper
                    && overrides.equals(that.overrides);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, System.identityHashCode(mapper), overrides);
        }

        @Override
        public String toString() {
            return "RecordedProfile[id=" + id + ", overrides=" + overrides + "]";
        }
    }
}
