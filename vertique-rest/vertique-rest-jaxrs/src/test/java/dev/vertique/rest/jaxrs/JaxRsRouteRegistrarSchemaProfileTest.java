// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.core.json.JsonProfile;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proof that {@link JaxRsRouteRegistrar#registerAll} hands every operation's <em>resolved</em> JSON
 * profile to the {@link OperationSchemaSource} — the profile whose mapper parses that operation's
 * body — rather than a fixed profile or the global {@code json.jsonProfile} read directly (FR-001,
 * AC-001.1).
 *
 * <p>The five selection tiers are exercised across three router builds, each tier distinguished from
 * its neighbours by a different profile id so no two tiers can be confused:
 *
 * <ol>
 *   <li>method {@code @JsonProfile("system")} overriding the class annotation — build (1);</li>
 *   <li>class {@code @JsonProfile("vertique-strict")} — build (1);</li>
 *   <li>{@code jaxrs.jsonProfile=system} on an unannotated resource — build (2);</li>
 *   <li>{@code json.jsonProfile=vertique-strict} on an unannotated resource — build (3);</li>
 *   <li>the {@code vertique} floor, nothing configured anywhere — build (1).</li>
 * </ol>
 *
 * <p>The three built-in ids ({@code system}, {@code vertique}, {@code vertique-strict}) are seeded by
 * {@link dev.vertique.json.DefaultJsonMapperProfileRegistry}, so the builds need no application
 * profile. The operation ids are the fixtures' method names (the scanner's fallback when no
 * {@code @Operation(operationId = …)} is present), which is what the captured map is keyed by.
 */
class JaxRsRouteRegistrarSchemaProfileTest {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    // --- Fixtures: resource classes named for the profile their annotations select ---

    /**
     * Class-annotated with {@code vertique-strict}; {@code systemOverride} overrides it with
     * {@code system}. The two methods exercise the method tier and the class tier in one build.
     */
    @Path("/strict")
    @JsonProfile("vertique-strict")
    static class VertiqueStrictResource {

        @POST
        @Path("/override")
        @JsonProfile("system")
        public String systemOverride(String body) {
            return body;
        }

        @POST
        public String strictFromClass(String body) {
            return body;
        }
    }

    /**
     * No {@code @JsonProfile} at either level: {@code plain} resolves through {@code jaxrs.jsonProfile},
     * then {@code json.jsonProfile}, then the {@code vertique} floor, according to each build's config.
     */
    @Path("/plain")
    static class UnannotatedResource {

        @POST
        public String plain(String body) {
            return body;
        }
    }

    // --- Helpers ---

    /**
     * Returns a schema source that records {@code operationId -> profile.id()} for every operation the
     * registrar hands it and returns no schemas (the {@code none} strategy the harness installs never
     * reads them).
     *
     * @param captured the map every call writes its operation id and effective profile id into
     * @return the capturing source to pass to the registrar
     */
    private static OperationSchemaSource capturing(Map<String, String> captured) {
        return (op, profile) -> {
            captured.put(op.operationId(), profile.id().value());
            return OperationSchemas.empty();
        };
    }

    /**
     * Runs one router build with the supplied configuration and resources.
     *
     * @param jaxRsConfig the JAX-RS config supplying the {@code jaxrs.jsonProfile} tier
     * @param jsonConfig the global JSON config supplying the {@code json.jsonProfile} tier and floor
     * @param resources the resource instances this build registers
     * @return the operation id to effective profile id map captured during the build
     */
    private Map<String, String> buildWith(JaxRsConfig jaxRsConfig, JsonConfig jsonConfig, Object... resources) {
        Map<String, String> captured = new LinkedHashMap<>();
        RegistrarTestSupport.registerAll(
                new JaxRsRouteRegistrar(),
                Set.of(resources),
                Router.router(vertx),
                RegistrarTestSupport.TEST_MOUNT_META,
                Optional.of(capturing(captured)),
                jaxRsConfig,
                jsonConfig);
        return captured;
    }

    @Test
    @DisplayName("the registrar passes each operation's resolved profile to the schema source")
    void registrarPassesTheResolvedProfileToTheSchemaSource() {
        // given the annotated and unannotated fixtures and a capturing schema source, when each of the
        // three routers is built
        Map<String, String> annotationBuild = buildWith(
                JaxRsConfig.builder().build(),
                JsonConfig.defaults(),
                new VertiqueStrictResource(),
                new UnannotatedResource());
        Map<String, String> jaxrsDefaultBuild = buildWith(
                JaxRsConfig.builder().jsonProfile("system").build(), JsonConfig.defaults(), new UnannotatedResource());
        Map<String, String> globalDefaultBuild =
                buildWith(JaxRsConfig.builder().build(), new JsonConfig("vertique-strict"), new UnannotatedResource());

        // then each build's captured ids name the tier that selected them; the three builds are asserted
        // under assertAll so a failing build never hides the next one's ids
        assertAll(
                () -> assertEquals(
                        Map.of("systemOverride", "system", "strictFromClass", "vertique-strict", "plain", "vertique"),
                        annotationBuild,
                        "(1) the method tier must yield system, the class tier vertique-strict, and the"
                                + " unconfigured route the vertique floor"),
                () -> assertEquals(
                        Map.of("plain", "system"),
                        jaxrsDefaultBuild,
                        "(2) jaxrs.jsonProfile must select the profile handed to the schema source"),
                () -> assertEquals(
                        Map.of("plain", "vertique-strict"),
                        globalDefaultBuild,
                        "(3) json.jsonProfile must select the profile handed to the schema source"));
    }
}
