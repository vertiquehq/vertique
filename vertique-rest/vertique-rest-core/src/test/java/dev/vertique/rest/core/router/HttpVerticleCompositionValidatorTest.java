// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link HttpVerticle}'s package-private six-argument {@code @Inject} constructor
 * and its {@link MountCompositionValidator} seam (C-SPI): validators run only once mount-path
 * validation has passed, their combined violation messages are sorted lexicographically into the
 * existing {@code IllegalStateException("Invalid mount configuration:\n  ...")} shape, a throwing
 * validator fails start with its own exception, no validator ever sees an invalid-path composition,
 * and no mount router is created while a validator would still reject the composition. The
 * retained public five-argument constructor runs no validator at all.
 *
 * <p>Lives in the {@code router} package because the six-argument constructor is package-private.
 */
@ExtendWith(VertxExtension.class)
class HttpVerticleCompositionValidatorTest {

    private static final String INVALID_MOUNT_PATH = "not-a-real-path";
    private static final String VALID_MOUNT_PATH = "/valid/*";
    private static final String HELLO_PATH = "/valid/hello";

    /** Substrings of {@code HttpVerticle}'s existing, unsorted path-violation messages. */
    private static final String LEADING_SLASH_VIOLATION = "must start with '/'";

    private static final String TRAILING_STAR_VIOLATION = "must end with '/*'";

    /**
     * Two validator-produced violation strings, deliberately returned by the same validator in
     * this (non-alphabetical) order, so a combined result that were merely concatenated — never
     * globally sorted — could not coincidentally already read alphabetically.
     */
    private static final String VIOLATION_ZEBRA = "Zebra: mount conflict reported by validator one (first)";

    private static final String VIOLATION_APPLE = "Apple: mount conflict reported by validator one (second)";

    /** A second validator's single violation, alphabetically between {@link #VIOLATION_APPLE} and {@link #VIOLATION_ZEBRA}. */
    private static final String VIOLATION_MANGO = "Mango: mount conflict reported by validator two";

    private static final String THROWING_VALIDATOR_MESSAGE = "validator boom";

    // --- TP-001 ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("validatorCases")
    @DisplayName(
            "Composition validators run only once mount paths are valid, their combined violations are sorted into "
                    + "one IllegalStateException, and a throwing validator fails start with its own exception — "
                    + "always before any createRouter call")
    void validatorViolationsFailStartBeforeAnyRouter(ValidatorCase testCase, Vertx vertx, VertxTestContext ctx) {
        vertx.deployVerticle(testCase.verticle()).onComplete(ctx.failing(err -> {
            ctx.verify(() -> {
                testCase.assertFailure().accept(err);
                assertEquals(
                        0,
                        testCase.mount().createRouterCalls(),
                        testCase.name() + ": createRouter must never be called");
            });
            ctx.completeNow();
        }));
    }

    /**
     * TP-001's three cases: (a) an invalid mount path, beside a validator that must never run; (b) a
     * valid path, beside two validators whose combined violations must be sorted into one
     * {@link IllegalStateException}; (c) a valid path, beside a validator that throws.
     *
     * @return the three TP-001 cases, in contract order
     */
    private static Stream<ValidatorCase> validatorCases() {
        CountingRouterMount invalidPathMount = new CountingRouterMount(INVALID_MOUNT_PATH);
        RecordingValidator neverRunValidator = RecordingValidator.returning(List.of());
        ValidatorCase invalidPathCase = new ValidatorCase(
                "(a) invalid mount path: path violations reported, and the validator never runs",
                verticle(Set.of(invalidPathMount), Set.of(neverRunValidator)),
                invalidPathMount,
                err -> {
                    String message = err.getMessage();
                    assertTrue(
                            message.contains(LEADING_SLASH_VIOLATION),
                            () -> "expected the leading-slash violation in: " + message);
                    assertTrue(
                            message.contains(TRAILING_STAR_VIOLATION),
                            () -> "expected the trailing-star violation in: " + message);
                    assertTrue(
                            message.indexOf(LEADING_SLASH_VIOLATION) < message.indexOf(TRAILING_STAR_VIOLATION),
                            () -> "expected the path violations in mounted (detection) order: " + message);
                    assertEquals(
                            0,
                            neverRunValidator.calls(),
                            "the validator must not run when mount-path validation already failed");
                });

        CountingRouterMount sortedViolationsMount = new CountingRouterMount(VALID_MOUNT_PATH);
        RecordingValidator validatorOne = RecordingValidator.returning(List.of(VIOLATION_ZEBRA, VIOLATION_APPLE));
        RecordingValidator validatorTwo = RecordingValidator.returning(List.of(VIOLATION_MANGO));
        ValidatorCase sortedViolationsCase = new ValidatorCase(
                "(b) valid path: two validators' out-of-order violations are sorted into one IllegalStateException",
                verticle(Set.of(sortedViolationsMount), Set.of(validatorOne, validatorTwo)),
                sortedViolationsMount,
                err -> {
                    assertInstanceOf(
                            IllegalStateException.class,
                            err,
                            "sorted validator violations must fail start with an IllegalStateException");
                    String expected = "Invalid mount configuration:\n  "
                            + String.join("\n  ", List.of(VIOLATION_APPLE, VIOLATION_MANGO, VIOLATION_ZEBRA));
                    assertEquals(
                            expected,
                            err.getMessage(),
                            "every validator message must appear exactly once, in lexicographic order");
                });

        CountingRouterMount throwingValidatorMount = new CountingRouterMount(VALID_MOUNT_PATH);
        IllegalArgumentException validatorException = new IllegalArgumentException(THROWING_VALIDATOR_MESSAGE);
        RecordingValidator throwingValidator = RecordingValidator.throwing(validatorException);
        ValidatorCase throwingValidatorCase = new ValidatorCase(
                "(c) valid path: a throwing validator fails start with its own exception",
                verticle(Set.of(throwingValidatorMount), Set.of(throwingValidator)),
                throwingValidatorMount,
                err -> assertSame(
                        validatorException,
                        err,
                        "start must fail with the validator's own exception, never wrapped or replaced"));

        return Stream.of(invalidPathCase, sortedViolationsCase, throwingValidatorCase);
    }

    // --- TP-002 ---

    @Test
    @DisplayName(
            "The public five-argument constructor runs no composition validator: deployment succeeds and the route answers 200")
    void fiveArgumentConstructorRunsNoValidators(Vertx vertx, VertxTestContext ctx) {
        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(),
                Set.of(),
                Set.of(helloMount()),
                Set.of());

        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> {
            int port = (int) vertx.sharedData().getLocalMap("vertique").get("http.port");
            WebClient client = WebClient.create(vertx);
            client.get(port, "127.0.0.1", HELLO_PATH).send().onComplete(ctx.succeeding(response -> {
                try {
                    ctx.verify(() -> assertEquals(200, response.statusCode(), "the route must answer 200"));
                } finally {
                    client.close();
                }
                ctx.completeNow();
            }));
        }));
    }

    /**
     * A non-JAX-RS mount serving {@code GET /hello} with a 200 response, mounted at {@link #VALID_MOUNT_PATH}.
     *
     * @return the mount
     */
    private static RouterMount helloMount() {
        return new RouterMount() {
            @Override
            public String mountPath() {
                return VALID_MOUNT_PATH;
            }

            @Override
            public Future<Router> createRouter(Vertx vertx) {
                Router router = Router.router(vertx);
                router.get("/hello")
                        .handler(rc -> rc.response().setStatusCode(200).end());
                return Future.succeededFuture(router);
            }
        };
    }

    // --- Shared helpers and fixtures ---

    /**
     * Builds an {@code HttpVerticle} from the given mounts and validators through the package-private
     * six-argument {@code @Inject} constructor, bound to loopback on a dynamic port.
     *
     * @param mounts     the router mounts
     * @param validators the composition validators
     * @return the constructed verticle
     */
    private static HttpVerticle verticle(Set<RouterMount> mounts, Set<MountCompositionValidator> validators) {
        return new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0),
                Set.of(),
                Set.of(),
                mounts,
                Set.of(),
                validators);
    }

    /**
     * One TP-001 case: a name, the pre-built {@link HttpVerticle} to deploy, its counting mount (to
     * assert {@code createRouter} was never called), and the failure assertion to run against the
     * deployment's cause.
     *
     * @param name          the case's display name, also used in every assertion failure message
     * @param verticle      the verticle to deploy
     * @param mount         the case's counting mount
     * @param assertFailure asserts the expected shape of the deployment failure
     */
    private record ValidatorCase(
            String name, HttpVerticle verticle, CountingRouterMount mount, Consumer<Throwable> assertFailure) {

        @Override
        public String toString() {
            return name;
        }
    }

    /** A {@link RouterMount} at a configurable path that counts {@link #createRouter} invocations. */
    private static final class CountingRouterMount implements RouterMount {

        private final String mountPath;
        private final AtomicInteger createRouterCalls = new AtomicInteger();

        private CountingRouterMount(String mountPath) {
            this.mountPath = mountPath;
        }

        @Override
        public String mountPath() {
            return mountPath;
        }

        @Override
        public Future<Router> createRouter(Vertx vertx) {
            createRouterCalls.incrementAndGet();
            return Future.succeededFuture(Router.router(vertx));
        }

        int createRouterCalls() {
            return createRouterCalls.get();
        }
    }

    /**
     * A {@link MountCompositionValidator} that counts its {@link #validate(List)} calls and either
     * returns a fixed violation list or throws a fixed exception, as configured by its factory method.
     */
    private static final class RecordingValidator implements MountCompositionValidator {

        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> violations;
        private final RuntimeException toThrow;

        private RecordingValidator(List<String> violations, RuntimeException toThrow) {
            this.violations = violations;
            this.toThrow = toThrow;
        }

        static RecordingValidator returning(List<String> violations) {
            return new RecordingValidator(violations, null);
        }

        static RecordingValidator throwing(RuntimeException toThrow) {
            return new RecordingValidator(null, toThrow);
        }

        @Override
        public List<String> validate(List<RouterMount> mounts) {
            calls.incrementAndGet();
            if (toThrow != null) {
                throw toThrow;
            }
            return violations;
        }

        int calls() {
            return calls.get();
        }
    }
}
