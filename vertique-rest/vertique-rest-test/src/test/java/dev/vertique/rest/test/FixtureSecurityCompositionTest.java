// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves that {@link RestTestFixtureModule} composes with a {@link SecurityPolicyValidator} supplied
 * from outside it — the property that lets a consumer include {@code AuthModule} and get the
 * framework's real {@code DefaultSecurityPolicyValidator} instead of the fixture's {@code null}
 * stand-in.
 *
 * <p>The validator binding lives in {@link RestTestNoSecurityModule}, a module a consumer includes
 * <em>only</em> when it wants no security. Both postures are exercised here: {@link
 * ExternalSecurityMountComponent} omits that module and binds a validator of its own, while {@link
 * FixtureSelfTestComponent} includes it and gets the skip-validation stand-in.
 *
 * <p><b>What this test cannot do.</b> It does not include {@code AuthModule} itself —
 * {@code vertique-rest-test} does not depend on {@code vertique-rest-security}, and pulling that
 * artifact onto this module's test classpath merely to name one module would invert the layering. The
 * binding key is the whole of the conflict, though, and a {@code @BindsInstance} claims the same
 * unqualified key an {@code AuthModule} {@code @Provides} claims: were the stand-in ever folded back
 * into {@link RestTestFixtureModule}, {@link ExternalSecurityMountComponent} would fail annotation
 * processing with the identical duplicate-binding error a consumer's {@code AuthModule} graph hits.
 * The compile of that component is therefore the standing guard, and the assertions below prove the
 * externally bound validator is genuinely wired into the mount rather than merely resolvable.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class FixtureSecurityCompositionTest {

    /** Timeout for the router builds this test awaits. */
    private static final long AWAIT_SECONDS = 5;

    private static Vertx vertx;

    @BeforeAll
    static void createVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void closeVertx() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    // --- Composition with a validator from elsewhere ---

    @Test
    @DisplayName("a graph without RestTestNoSecurityModule resolves a SecurityPolicyValidator bound elsewhere")
    void externallyBoundValidatorRunsDuringRouterBuild() throws Exception {
        RecordingSecurityPolicyValidator validator = new RecordingSecurityPolicyValidator();
        ExternalSecurityMountComponent component = DaggerExternalSecurityMountComponent.factory()
                .create(vertx, noneStrategyConfig(), RestTestContributions.none(), validator);

        Router router = buildRouter(component.testMount());

        assertThat(router).isNotNull();
        assertThat(validator.validatedOperationIds)
                .as("the registrar must have called the externally bound validator, not a fixture stand-in")
                .containsExactly("externalSecurityPing");
    }

    // --- The opt-in stand-in still works from its own module ---

    @Test
    @DisplayName("a graph including RestTestNoSecurityModule builds the same mount with policy validation skipped")
    void noSecurityModuleSuppliesTheNullStandIn() throws Exception {
        RestTestMount mount = DaggerFixtureSelfTestComponent.factory()
                .create(vertx, noneStrategyConfig(), RestTestContributions.none())
                .testMount();

        assertThat(buildRouter(mount))
                .as("the null validator is what makes an unsecured fixture graph buildable at all")
                .isNotNull();
    }

    // --- Helpers ---

    /**
     * Builds a router over the single probe resource, the way {@code RestTestMounts.router} does.
     *
     * @param mount the mount handle to build from
     * @return the built API router
     * @throws Exception when the build fails or does not settle in time
     */
    private static Router buildRouter(RestTestMount mount) throws Exception {
        return mount.factory()
                .create("/*", "openapi.json", Set.of(new PingResource()))
                .createRouter(vertx)
                .toCompletionStage()
                .toCompletableFuture()
                .get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Returns a fresh configuration selecting the {@code none} validation strategy, without which a
     * fixture graph carrying no validation module cannot resolve its configured strategy id.
     *
     * @return the configuration object
     */
    private static JsonObject noneStrategyConfig() {
        return new JsonObject().put("jaxrs", new JsonObject().put("validationStrategy", "none"));
    }

    /**
     * Records the operations it is asked to validate and reports no violations, so the router build
     * succeeds and the recording alone is the signal.
     */
    private static final class RecordingSecurityPolicyValidator implements SecurityPolicyValidator {

        /** Operation ids seen at route-registration time, in registration order. */
        private final List<String> validatedOperationIds = new ArrayList<>();

        @Override
        public List<SecurityPolicyViolation> validate(RestOperationDescriptor op, SecurityPolicy policy) {
            validatedOperationIds.add(op.operationId());
            return List.of();
        }
    }

    /** JAX-RS resource used only to give the mount an operation to validate. */
    @Path("/composition")
    public static class PingResource {

        /**
         * Returns a constant body.
         *
         * @return the literal {@code "pong"}
         */
        @GET
        @Path("/ping")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "externalSecurityPing")
        public String ping() {
            return "pong";
        }
    }
}
