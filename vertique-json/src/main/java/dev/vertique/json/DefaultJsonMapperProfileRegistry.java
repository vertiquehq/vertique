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
 *   <li>The built-in {@code system} profile ({@link SystemJsonMapperProfile}) is seeded
 *       <strong>separately</strong> from the application set and is <strong>not</strong> probed —
 *       its mapper is a trusted copy of Vert.x's {@code DatabindCodec.mapper()} carrying the
 *       baseline recipe (FR-JSON-007A, FR-JSON-015E).
 *   <li>The built-in {@code vertique} profile ({@link VertiqueJsonMapperProfile}) is likewise seeded
 *       <strong>separately</strong> and is <strong>not</strong> probed (FR-JSON-043, FR-JSON-045): its
 *       opinionated mapper applies {@code NON_NULL} inclusion, which the structural round-trip probe
 *       (it round-trips a {@code JsonObject} with a null field) would falsely reject.
 *   <li>The built-in {@code vertique-strict} profile ({@link VertiqueStrictJsonMapperProfile}) is
 *       likewise seeded <strong>separately</strong> and is <strong>not</strong> probed (json-004): it
 *       inherits the same {@code NON_NULL} inclusion, and additionally requires the string wire form
 *       for {@code BigDecimal} — neither of which the probe payload is written against.
 *   <li>Each application-contributed profile is validated, in this order and <strong>before</strong>
 *       the probe runs: the retired {@code vertx} id is rejected naming the rename to {@code system}
 *       (it is a <em>retired reserved</em> id and cannot be re-registered); an id equal to one of the
 *       reserved built-in ids ({@link SystemJsonMapperProfile#ID},
 *       {@link VertiqueJsonMapperProfile#ID}, {@link VertiqueStrictJsonMapperProfile#ID}) is rejected
 *       (FR-JSON-005, FR-JSON-044, json-004); a mapper with Jackson <em>default typing</em> active is
 *       rejected (every profile role binds untrusted input); a duplicate application id is rejected
 *       (FR-JSON-004); only then is the mapper run through the structural round-trip probe
 *       (FR-JSON-015C).
 * </ol>
 *
 * <p>The resulting {@link JsonProfileId}-keyed map is immutable; lookups are {@code O(1)}
 * (NFR-JSON-001). Resolving an unknown id throws {@link JsonProfileConfigurationException} with a
 * message listing every discovered id (FR-JSON-008); resolving the retired {@code vertx} id names
 * the rename instead.
 */
@Singleton
public final class DefaultJsonMapperProfileRegistry implements JsonMapperProfileRegistry {

    /** The retired reserved id that {@link JsonProfileId#SYSTEM} replaced. */
    private static final JsonProfileId RETIRED_VERTX_ID = JsonProfileId.of("vertx");

    /** Message naming the rename, used for every rejection of the retired id. */
    private static final String RENAMED_MESSAGE =
            "the 'vertx' JSON profile was renamed 'system'; it is retired and cannot be selected or re-registered"
                    + " — configure 'system' instead (it is the same Vert.x mapper recipe, now with Optional and"
                    + " java.time support)";

    private final Map<JsonProfileId, JsonMapperProfile> profilesById;

    /**
     * Builds and validates the registry from the application-contributed profile set.
     *
     * @param applicationProfiles the application {@code @IntoSet} profiles (may be empty); the
     *     reserved {@code system}, {@code vertique} and {@code vertique-strict} profiles are never
     *     expected here and are seeded separately
     * @throws JsonProfileConfigurationException if an application profile uses the retired
     *     {@code vertx} id or the reserved {@code system}, {@code vertique} or
     *     {@code vertique-strict} id, exposes a mapper with Jackson default typing active, two
     *     application profiles share an id, or a non-built-in mapper fails the structural round-trip
     *     probe
     */
    @Inject
    public DefaultJsonMapperProfileRegistry(Set<JsonMapperProfile> applicationProfiles) {
        Map<JsonProfileId, JsonMapperProfile> byId = new LinkedHashMap<>();

        // --- Seed the built-in system profile separately; its trusted mapper is not probed, but it
        // IS checked for default typing: it is a copy of the process-global mapper, which classpath
        // libraries (or a not-yet-retired customizer) could have mutated before the copy. ---
        JsonMapperProfile systemProfile = new SystemJsonMapperProfile();
        seedBuiltIn(byId, systemProfile);

        // --- Seed the built-in vertique profile separately; its opinionated mapper is probe-exempt
        // (the structural probe round-trips a null-bearing JsonObject, which NON_NULL would drop). ---
        JsonMapperProfile vertiqueProfile = new VertiqueJsonMapperProfile();
        seedBuiltIn(byId, vertiqueProfile);

        // --- Seed the built-in vertique-strict profile separately; probe-exempt for the same
        // NON_NULL reason, plus its BigDecimal properties require the string wire form. ---
        JsonMapperProfile vertiqueStrictProfile = new VertiqueStrictJsonMapperProfile();
        seedBuiltIn(byId, vertiqueStrictProfile);

        // --- Validate and register each application profile. Every guard runs BEFORE the probe, so a
        // rejected profile fails with its own diagnostic rather than an incidental probe failure. ---
        for (JsonMapperProfile profile : applicationProfiles) {
            JsonProfileId id = profile.id();
            if (RETIRED_VERTX_ID.equals(id)) {
                throw new JsonProfileConfigurationException(RENAMED_MESSAGE);
            }
            if (SystemJsonMapperProfile.ID.equals(id)
                    || VertiqueJsonMapperProfile.ID.equals(id)
                    || VertiqueStrictJsonMapperProfile.ID.equals(id)) {
                throw new JsonProfileConfigurationException(
                        "application profiles must not override the reserved '" + id.value() + "' profile");
            }
            if (hasDefaultTypingActive(profile.mapper())) {
                throw new JsonProfileConfigurationException("JSON profile '" + id.value()
                        + "' activates Jackson default typing; every profile binds untrusted input, so default typing"
                        + " is not allowed — use annotation-driven @JsonTypeInfo instead");
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
     * <p>The retired {@code vertx} id fails with a message naming the rename to {@code system}, so a
     * configuration carried over from before the rename fails at startup with the remedy in hand.
     *
     * @param id the profile id to resolve
     * @return the named profile
     * @throws JsonProfileConfigurationException if {@code id} is not registered
     */
    @Override
    public JsonMapperProfile profile(JsonProfileId id) {
        if (id == null) {
            throw new JsonProfileConfigurationException(
                    "JSON profile id must not be null. Known profiles: " + sortedIdValues());
        }
        if (RETIRED_VERTX_ID.equals(id)) {
            throw new JsonProfileConfigurationException(RENAMED_MESSAGE);
        }
        JsonMapperProfile profile = profilesById.get(id);
        if (profile == null) {
            throw new JsonProfileConfigurationException(
                    "Unknown JSON profile id '" + id.value() + "'. Known profiles: " + sortedIdValues());
        }
        return profile;
    }

    /**
     * {@inheritDoc}
     *
     * @return an immutable set of every registered profile id, including the {@code system},
     *     {@code vertique} and {@code vertique-strict} built-ins
     */
    @Override
    public Set<JsonProfileId> profileIds() {
        return profilesById.keySet();
    }

    // --- Application-profile guards ---

    /**
     * Reports whether Jackson polymorphic <em>default typing</em> is active on {@code mapper}.
     * Annotation-driven {@code @JsonTypeInfo} is unaffected — only a blanket
     * {@code activateDefaultTyping(...)} installs a default typer.
     *
     * @param mapper the profile mapper to inspect
     * @return {@code true} when the mapper resolves a default typer for an untyped base
     */
    /**
     * Seeds one built-in profile, refusing it when its mapper carries Jackson default typing. The
     * built-ins are probe-exempt (trusted recipes), but the {@code system} recipe copies the
     * process-global mapper, so the default-typing rule is enforced on every profile role.
     */
    private static void seedBuiltIn(Map<JsonProfileId, JsonMapperProfile> byId, JsonMapperProfile builtIn) {
        probe(builtIn.id(), builtIn.mapper());
        if (hasDefaultTypingActive(builtIn.mapper())) {
            throw new JsonProfileConfigurationException(
                    "built-in JSON profile '" + builtIn.id().value()
                            + "' inherited Jackson default typing from the process mapper (DatabindCodec.mapper() was"
                            + " mutated before the registry was built); default typing is not allowed on any profile");
        }
        byId.put(builtIn.id(), builtIn);
    }

    private static boolean hasDefaultTypingActive(ObjectMapper mapper) {
        return mapper.getDeserializationConfig().getDefaultTyper(null) != null;
    }

    // --- Round-trip probe (FR-JSON-015C) ---

    /**
     * Runs the structural round-trip probe for an application profile mapper: serializes then
     * deserializes a representative {@link JsonObject} and {@link JsonArray} and requires structural
     * equality after decode. Any thrown exception, or a structural mismatch, is a probe failure.
     *
     * @param id the id of the profile being probed (named in the failure message)
     * @param mapper the profile mapper to probe
     * @throws JsonProfileConfigurationException if the probe round-trip throws or loses structure
     */
    private static void probe(JsonProfileId id, ObjectMapper mapper) {
        // No explicit null field: a mapper that omits nulls is applying a serialization-inclusion
        // policy, not corrupting structure, and NON_NULL inclusion is exactly what JacksonDefaults
        // applies. Probing a null-bearing payload rejected every application profile seeded the way
        // ADR-0135 and the module document both sanction (GH-240).
        JsonObject objectSample = new JsonObject()
                .put("string", "value")
                .put("number", 42)
                .put("boolean", true)
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
     * @return e.g. {@code [legacy-crm, payments-v1, system, vertique, vertique-strict]}
     */
    private String sortedIdValues() {
        return profilesById.keySet().stream()
                .map(JsonProfileId::value)
                .collect(Collectors.toCollection(TreeSet::new))
                .toString();
    }
}
