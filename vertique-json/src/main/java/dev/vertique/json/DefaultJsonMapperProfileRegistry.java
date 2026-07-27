// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Default {@link JsonMapperProfileRegistry} that validates the discovered profiles at construction
 * time and exposes constant-time, immutable resolution thereafter.
 *
 * <p>Construction order (NFR-JSON-002A — all validation is eager, in the {@code @Inject} ctor):
 *
 * <ol>
 *   <li>The built-in {@code vertx} profile ({@link VertxJsonMapperProfile}) is seeded
 *       <strong>separately</strong> from the application set and is <strong>not</strong> probed —
 *       its mapper is Vert.x's trusted {@code DatabindCodec.mapper()} (FR-JSON-007A, FR-JSON-015E).
 *   <li>The built-in {@code vertique} profile ({@link VertiqueJsonMapperProfile}) is likewise seeded
 *       <strong>separately</strong> and is <strong>not</strong> probed (FR-JSON-043, FR-JSON-045): its
 *       opinionated mapper applies {@code NON_NULL} inclusion, which the structural round-trip probe
 *       (it round-trips a {@code JsonObject} with a null field) would falsely reject.
 *   <li>The built-in {@code vertique-strict} profile ({@link VertiqueStrictJsonMapperProfile}) is
 *       likewise seeded <strong>separately</strong> and is <strong>not</strong> probed (json-004): it
 *       inherits the same {@code NON_NULL} inclusion, and additionally requires the string wire form
 *       for {@code BigDecimal} — neither of which the probe payload is written against.
 *   <li>Each application-contributed profile is validated: an id equal to {@link JsonProfileId#VERTX}
 *       or one of the reserved built-in ids ({@link VertiqueJsonMapperProfile#ID},
 *       {@link VertiqueStrictJsonMapperProfile#ID}) is rejected (FR-JSON-005, FR-JSON-044, json-004);
 *       a duplicate application id is rejected (FR-JSON-004); the mapper is run through a structural
 *       round-trip probe (FR-JSON-015C).
 * </ol>
 *
 * <p>The resulting {@link JsonProfileId}-keyed map is immutable; lookups are {@code O(1)}
 * (NFR-JSON-001). Resolving an unknown id throws {@link JsonProfileConfigurationException} with a
 * message listing every discovered id (FR-JSON-008).
 */
@Singleton
public final class DefaultJsonMapperProfileRegistry implements JsonMapperProfileRegistry {

    private final Map<JsonProfileId, JsonMapperProfile> profilesById;

    /**
     * Builds and validates the registry from the application-contributed profile set.
     *
     * @param applicationProfiles the application {@code @IntoSet} profiles (may be empty); the
     *     reserved {@code vertx}, {@code vertique} and {@code vertique-strict} profiles are never
     *     expected here and are seeded separately
     * @throws JsonProfileConfigurationException if an application profile uses the reserved
     *     {@code vertx}, {@code vertique} or {@code vertique-strict} id, two application profiles
     *     share an id, or a non-built-in mapper fails the structural round-trip probe
     */
    @Inject
    public DefaultJsonMapperProfileRegistry(Set<JsonMapperProfile> applicationProfiles) {
        Map<JsonProfileId, JsonMapperProfile> byId = new LinkedHashMap<>();

        // --- Seed the built-in vertx profile separately; its trusted mapper is not probed. ---
        JsonMapperProfile vertxProfile = new VertxJsonMapperProfile();
        byId.put(vertxProfile.id(), vertxProfile);

        // --- Seed the built-in vertique profile separately; its opinionated mapper is probe-exempt
        // (the structural probe round-trips a null-bearing JsonObject, which NON_NULL would drop). ---
        JsonMapperProfile vertiqueProfile = new VertiqueJsonMapperProfile();
        byId.put(vertiqueProfile.id(), vertiqueProfile);

        // --- Seed the built-in vertique-strict profile separately; probe-exempt for the same
        // NON_NULL reason, plus its BigDecimal properties require the string wire form. ---
        JsonMapperProfile vertiqueStrictProfile = new VertiqueStrictJsonMapperProfile();
        byId.put(vertiqueStrictProfile.id(), vertiqueStrictProfile);

        // --- Validate and register each application profile. ---
        for (JsonMapperProfile profile : applicationProfiles) {
            JsonProfileId id = profile.id();
            if (JsonProfileId.VERTX.equals(id)
                    || VertiqueJsonMapperProfile.ID.equals(id)
                    || VertiqueStrictJsonMapperProfile.ID.equals(id)) {
                throw new JsonProfileConfigurationException(
                        "application profiles must not override the reserved '" + id.value() + "' profile");
            }
            if (byId.containsKey(id)) {
                throw new JsonProfileConfigurationException(
                        "duplicate JSON profile id '" + id.value() + "' among application profiles");
            }
            probe(id, profile.mapper());
            byId.put(id, profile);
        }

        this.profilesById = Map.copyOf(byId);
    }

    /**
     * {@inheritDoc}
     *
     * @param id the profile id to resolve
     * @return the {@link ObjectMapper} backing the named profile
     * @throws JsonProfileConfigurationException if {@code id} is not registered
     */
    @Override
    public ObjectMapper mapper(JsonProfileId id) {
        return profile(id).mapper();
    }

    /**
     * {@inheritDoc}
     *
     * @param id the profile id to resolve
     * @return the named profile
     * @throws JsonProfileConfigurationException if {@code id} is not registered
     */
    @Override
    public JsonMapperProfile profile(JsonProfileId id) {
        JsonMapperProfile profile = profilesById.get(id);
        if (profile == null) {
            throw new JsonProfileConfigurationException("Unknown JSON profile id '" + (id == null ? null : id.value())
                    + "'. Known profiles: " + sortedIdValues());
        }
        return profile;
    }

    /**
     * {@inheritDoc}
     *
     * @return an immutable set of every registered profile id, including the {@code vertx},
     *     {@code vertique} and {@code vertique-strict} built-ins
     */
    @Override
    public Set<JsonProfileId> profileIds() {
        return profilesById.keySet();
    }

    // --- Round-trip probe (FR-JSON-015C) ---

    /**
     * Runs the structural round-trip probe for a non-{@code vertx} profile mapper: serializes then
     * deserializes a representative {@link JsonObject} and {@link JsonArray} and requires structural
     * equality after decode. Any thrown exception, or a structural mismatch, is a probe failure.
     *
     * @param id the id of the profile being probed (named in the failure message)
     * @param mapper the profile mapper to probe
     * @throws JsonProfileConfigurationException if the probe round-trip throws or loses structure
     */
    private static void probe(JsonProfileId id, ObjectMapper mapper) {
        JsonObject objectSample = new JsonObject()
                .put("string", "value")
                .put("number", 42)
                .put("boolean", true)
                .putNull("nullField")
                .put("nested", new JsonObject().put("inner", "x"))
                .put("array", new JsonArray().add(1).add("two"));
        JsonArray arraySample = new JsonArray().add("scalar").add(7).add(new JsonObject().put("k", "v"));

        try {
            JsonObject decodedObject = mapper.readValue(mapper.writeValueAsString(objectSample), JsonObject.class);
            JsonArray decodedArray = mapper.readValue(mapper.writeValueAsString(arraySample), JsonArray.class);
            if (!objectSample.equals(decodedObject) || !arraySample.equals(decodedArray)) {
                throw new JsonProfileConfigurationException("JSON profile '" + id.value()
                        + "' failed the round-trip probe: structure was not preserved across serialize/deserialize");
            }
        } catch (JsonProfileConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new JsonProfileConfigurationException(
                    "JSON profile '" + id.value() + "' failed the round-trip probe", e);
        }
    }

    /**
     * Returns the sorted profile-id values as a bracketed list for inclusion in error messages.
     *
     * @return e.g. {@code [legacy-crm, payments-v1, vertique, vertique-strict, vertx]}
     */
    private String sortedIdValues() {
        return profilesById.keySet().stream()
                .map(JsonProfileId::value)
                .collect(Collectors.toCollection(TreeSet::new))
                .toString();
    }
}
