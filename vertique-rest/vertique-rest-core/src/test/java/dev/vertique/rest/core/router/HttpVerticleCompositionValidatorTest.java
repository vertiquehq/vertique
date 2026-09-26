// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link HttpVerticle}'s package-private six-argument {@code @Inject} constructor
 * and its {@link MountCompositionValidator} seam (C-SPI): validators run only once mount-path
 * validation has passed, their combined violation messages are sorted lexicographically into the
 * existing {@code IllegalStateException("Invalid mount configuration:\n  ...")} shape, a throwing
 * validator — whether a {@link RuntimeException} or a {@link LinkageError} — fails start with its
 * own exception, no validator ever sees an invalid-path composition, no mount router is created
 * while a validator would still reject the composition, and validators run before overlap
 * detection so a rejected composition never logs an overlap warning. The retained public
 * five-argument constructor runs no validator at all.
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

    private static final String LINKAGE_ERROR_VALIDATOR_MESSAGE = "x";

    /** Two mount paths that overlap by containment: {@link #OVERLAP_INNER_PATH} is a prefix match under {@link #OVERLAP_OUTER_PATH}. */
    private static final String OVERLAP_OUTER_PATH = "/overlap/*";

    private static final String OVERLAP_INNER_PATH = "/overlap/nested/*";

    private static final String ALWAYS_VIOLATING_MESSAGE = "G2-11: composition always rejected";

    /** Substring of {@code HttpVerticle#detectOverlaps}'s containment-overlap warning message. */
    private static final String OVERLAP_WARNING_SUBSTRING = "is a prefix of";

    private Logger verticleLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureVerticleLogs() {
        verticleLogger = (Logger) LoggerFactory.getLogger(HttpVerticle.class);
        previousLevel = verticleLogger.getLevel();
        verticleLogger.setLevel(Level.WARN);
        appender = new ListAppender<>();
        appender.start();
        verticleLogger.addAppender(appender);
    }

    @AfterEach
    void releaseVerticleLogs() {
        verticleLogger.detachAppender(appender);
        appender.stop();
        verticleLogger.setLevel(previousLevel);
    }

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
     * TP-001's four cases: (a) an invalid mount path, beside a validator that must never run; (b) a
     * valid path, beside two validators whose combined violations must be sorted into one
     * {@link IllegalStateException}; (c) a valid path, beside a validator that throws a {@link
     * RuntimeException}; (d) a valid path, beside a validator that throws a {@link LinkageError}.
     *
     * @return the four TP-001 cases, in contract order
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

        CountingRouterMount linkageErrorValidatorMount = new CountingRouterMount(VALID_MOUNT_PATH);
        NoClassDefFoundError validatorLinkageError = new NoClassDefFoundError(LINKAGE_ERROR_VALIDATOR_MESSAGE);
        RecordingValidator linkageErrorValidator = RecordingValidator.throwingLinkageError(validatorLinkageError);
        ValidatorCase linkageErrorValidatorCase = new ValidatorCase(
                "(d) valid path: a validator throwing a LinkageError fails start with that error",
                verticle(Set.of(linkageErrorValidatorMount), Set.of(linkageErrorValidator)),
                linkageErrorValidatorMount,
                err -> assertSame(
                        validatorLinkageError,
                        err,
                        "start must fail with the validator's own LinkageError, never wrapped or replaced"));

        return Stream.of(invalidPathCase, sortedViolationsCase, throwingValidatorCase, linkageErrorValidatorCase);
    }

    /**
     * Calls {@link HttpVerticle#start(Promise)} directly, bypassing Vert.x's own deployment
     * machinery entirely. Vert.x's deployment layer already fails a deployment whose {@code start}
     * throws any {@link Throwable} (not only a {@link RuntimeException}), so the {@code
     * deployVerticle}-based case (d) above cannot by itself distinguish {@code HttpVerticle}
     * catching the {@link LinkageError} from Vert.x merely catching it one layer up. This test
     * proves the catch lives in {@code start()} itself: without it, the error propagates out of
     * this direct call uncaught; with it, {@code start()} returns normally having failed the
     * promise itself.
     */
    @Test
    @DisplayName("start() itself (not only Vert.x's deployment layer) catches a validator's LinkageError and fails "
            + "the promise with it, returning normally")
    void startItselfCatchesValidatorLinkageError() {
        CountingRouterMount mount = new CountingRouterMount(VALID_MOUNT_PATH);
        NoClassDefFoundError validatorLinkageError = new NoClassDefFoundError(LINKAGE_ERROR_VALIDATOR_MESSAGE);
        RecordingValidator validator = RecordingValidator.throwingLinkageError(validatorLinkageError);
        HttpVerticle verticle = verticle(Set.of(mount), Set.of(validator));
        Promise<Void> promise = Promise.promise();

        verticle.start(promise);

        assertTrue(promise.future().failed(), "start() must fail the promise itself, not merely propagate the error");
        assertSame(
                validatorLinkageError,
                promise.future().cause(),
                "the promise must fail with the validator's own LinkageError, never wrapped or replaced");
        assertEquals(0, mount.createRouterCalls(), "createRouter must never be called");
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

    // --- TP-003 ---

    @Test
    @DisplayName("Composition validators run before overlap detection: an always-violating validator fails start "
            + "and HttpVerticle logs no overlap warning for the (valid but overlapping) mounts")
    void validatorsRunBeforeDetectOverlaps(Vertx vertx, VertxTestContext ctx) {
        CountingRouterMount outerMount = new CountingRouterMount(OVERLAP_OUTER_PATH);
        CountingRouterMount innerMount = new CountingRouterMount(OVERLAP_INNER_PATH);
        RecordingValidator alwaysViolatingValidator = RecordingValidator.returning(List.of(ALWAYS_VIOLATING_MESSAGE));
        HttpVerticle verticle = verticle(Set.of(outerMount, innerMount), Set.of(alwaysViolatingValidator));

        vertx.deployVerticle(verticle).onComplete(ctx.failing(err -> {
            ctx.verify(() -> {
                assertInstanceOf(IllegalStateException.class, err, "the always-violating validator must fail start");
                assertTrue(
                        err.getMessage().contains(ALWAYS_VIOLATING_MESSAGE),
                        () -> "expected the validator's violation in: " + err.getMessage());
                assertEquals(0, outerMount.createRouterCalls(), "createRouter must never be called");
                assertEquals(0, innerMount.createRouterCalls(), "createRouter must never be called");
                assertTrue(
                        overlapWarnings().isEmpty(),
                        () -> "detectOverlaps must never run once a validator has already failed start, but got: "
                                + overlapWarnings());
            });
            ctx.completeNow();
        }));
    }

    /**
     * WARN events logged by {@link HttpVerticle} whose message names the containment-overlap
     * condition ({@link #OVERLAP_WARNING_SUBSTRING}).
     *
     * @return the matching WARN messages, in emission order
     */
    private List<String> overlapWarnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getLoggerName().equals(HttpVerticle.class.getName()))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(OVERLAP_WARNING_SUBSTRING))
                .toList();
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
     * returns a fixed violation list, throws a fixed {@link RuntimeException}, or throws a fixed
     * {@link LinkageError}, as configured by its factory method.
     */
    private static final class RecordingValidator implements MountCompositionValidator {

        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> violations;
        private final RuntimeException toThrowRuntime;
        private final LinkageError toThrowLinkageError;

        private RecordingValidator(
                List<String> violations, RuntimeException toThrowRuntime, LinkageError toThrowLinkageError) {
            this.violations = violations;
            this.toThrowRuntime = toThrowRuntime;
            this.toThrowLinkageError = toThrowLinkageError;
        }

        static RecordingValidator returning(List<String> violations) {
            return new RecordingValidator(violations, null, null);
        }

        static RecordingValidator throwing(RuntimeException toThrow) {
            return new RecordingValidator(null, toThrow, null);
        }

        static RecordingValidator throwingLinkageError(LinkageError toThrow) {
            return new RecordingValidator(null, null, toThrow);
        }

        @Override
        public List<String> validate(List<RouterMount> mounts) {
            calls.incrementAndGet();
            if (toThrowRuntime != null) {
                throw toThrowRuntime;
            }
            if (toThrowLinkageError != null) {
                throw toThrowLinkageError;
            }
            return violations;
        }

        int calls() {
            return calls.get();
        }
    }
}
