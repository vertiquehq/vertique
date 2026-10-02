// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.RestApplications;
import dev.vertique.rest.jaxrs.publication.RestApplications.ContractOrigin;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit-level proofs for {@link ContractLoadCheck}, the publication sink that turns a mount's
 * unloadable {@code openapi-contract} contract into a startup failure: for a mount bound under the
 * {@code openapi-contract} strategy, {@link ContractLoadCheck#mountBuilt} fails with a value-free
 * {@link RestConfigurationException} naming the application and the setting its contract location came
 * from (or, for a hand-built mount, the mount path) and the reason class, and it succeeds for a loadable
 * contract, for another strategy, and for a mount the strategy never bound. The returned future
 * completes on the caller's Vert.x context, even while the contract is still loading on another one.
 *
 * <p>Every contract is a file in a temporary directory whose name, {@code servers} URL, and
 * {@code info.description} carry {@value #MARKER}; no failure may echo it.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ContractLoadCheckTest {

    /** The marker every contract fixture carries in its name and content; no failure may echo it. */
    private static final String MARKER = "zq16marker";

    /** The longest any asynchronous step is awaited. */
    private static final long BOUND_SECONDS = 10;

    /** The application whose mount the check is asked about. */
    private static final String APPLICATION = "orders";

    /** The application's mount path as registered. */
    private static final String APPLICATION_MOUNT_PATH = "/api/orders/*";

    /** The application mount's id. */
    private static final String APPLICATION_MOUNT_ID = "jaxrs:/api/orders/*";

    /** A hand-built mount's path. */
    private static final String HAND_BUILT_MOUNT_PATH = "/api/handbuilt/*";

    /** A hand-built mount's id. */
    private static final String HAND_BUILT_MOUNT_ID = "jaxrs:/api/handbuilt/*";

    /** The fixed part of every failure, after the mount's identification. */
    private static final String CANNOT_BE_LOADED =
            " cannot be loaded for request-validation strategy 'openapi-contract': ";

    /** The reason for a location whose extension is not json, yaml, or yml. */
    private static final String EXTENSION_REASON = "its location must end in .json, .yaml, or .yml";

    /** The reason for a contract file that cannot be read. */
    private static final String UNREADABLE_REASON = "the file cannot be read";

    /** The reason for a contract with a relative or otherwise malformed server URL. */
    private static final String SERVERS_REASON =
            "a servers url is not a valid absolute URL; use an absolute URL or omit servers";

    /** The reason for any other contract vertx-openapi rejects. */
    private static final String INVALID_REASON = "the file is not a valid OpenAPI contract";

    /** A loadable contract with no {@code servers}. */
    private static final String VALID_CONTRACT =
            "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"Orders\",\"version\":\"1\"," + "\"description\":\"" + MARKER
                    + " description\"},\"paths\":{}}";

    /** A contract whose only server URL is relative. */
    private static final String RELATIVE_SERVERS_CONTRACT = "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"Orders\","
            + "\"version\":\"1\",\"description\":\"" + MARKER + " description\"},\"servers\":[{\"url\":\"/" + MARKER
            + "/api\"}],\"paths\":{}}";

    /** A JSON document that is not an OpenAPI contract: it has no {@code info}. */
    private static final String MISSING_INFO_CONTRACT = "{\"openapi\":\"3.0.3\",\"paths\":{\"/" + MARKER + "\":{}}}";

    /** A document declaring an OpenAPI version vertx-openapi does not support. */
    private static final String UNSUPPORTED_VERSION_CONTRACT = "{\"openapi\":\"2.0\",\"info\":{\"title\":\"Orders\","
            + "\"version\":\"1\",\"description\":\"" + MARKER + " description\"},\"paths\":{}}";

    /** A file that is not JSON at all. */
    private static final String UNDECODABLE_CONTRACT = MARKER + " is not a contract {";

    /** The declaring interface of the application the check is asked about. */
    interface OrdersApi {}

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------------------------------------
    // Reason classes
    // ---------------------------------------------------------------------------------------------

    /**
     * One unloadable contract location.
     *
     * @param label    the case, as the report names it
     * @param fileName the file name inside the temporary directory
     * @param kind     what exists at the location
     * @param content  the file content, for {@link Kind#FILE}
     * @param reason   the reason the failure must give
     */
    record Unloadable(String label, String fileName, Kind kind, String content, String reason) {

        @Override
        public String toString() {
            return label;
        }
    }

    /** What exists at a contract location. */
    enum Kind {
        /** A regular file with the case's content. */
        FILE,
        /** Nothing. */
        ABSENT,
        /** A directory. */
        DIRECTORY
    }

    static Stream<Unloadable> unloadableContracts() {
        return Stream.of(
                new Unloadable(
                        "a relative servers url",
                        MARKER + "-relative-servers.json",
                        Kind.FILE,
                        RELATIVE_SERVERS_CONTRACT,
                        SERVERS_REASON),
                new Unloadable(
                        "a readable contract with a .txt extension",
                        MARKER + "-contract.txt",
                        Kind.FILE,
                        VALID_CONTRACT,
                        EXTENSION_REASON),
                new Unloadable(
                        "a missing file with a .txt extension: the extension is checked first",
                        MARKER + "-missing.txt",
                        Kind.ABSENT,
                        null,
                        EXTENSION_REASON),
                new Unloadable(
                        "a readable contract without an extension",
                        MARKER + "-contract",
                        Kind.FILE,
                        VALID_CONTRACT,
                        EXTENSION_REASON),
                new Unloadable("a missing .json file", MARKER + "-missing.json", Kind.ABSENT, null, UNREADABLE_REASON),
                new Unloadable(
                        "a directory named like a .json file",
                        MARKER + "-directory.json",
                        Kind.DIRECTORY,
                        null,
                        UNREADABLE_REASON),
                new Unloadable(
                        "a document without info",
                        MARKER + "-missing-info.json",
                        Kind.FILE,
                        MISSING_INFO_CONTRACT,
                        INVALID_REASON),
                new Unloadable(
                        "a document of an unsupported OpenAPI version",
                        MARKER + "-unsupported-version.yaml",
                        Kind.FILE,
                        UNSUPPORTED_VERSION_CONTRACT,
                        INVALID_REASON),
                new Unloadable(
                        "a file that is not JSON",
                        MARKER + "-undecodable.json",
                        Kind.FILE,
                        UNDECODABLE_CONTRACT,
                        INVALID_REASON));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unloadableContracts")
    @DisplayName("an application mount whose contract cannot be loaded fails with the reason class and no value")
    void unloadableApplicationContractFailsWithItsReason(Unloadable unloadable, Vertx vertx) throws Exception {
        // Given: an application mount bound under openapi-contract to the unloadable location
        Path location = place(unloadable);
        OpenApiContractValidationStrategy strategy = strategy(vertx, null);
        strategy.bindToMount(applicationMount(location));
        ContractLoadCheck check = new ContractLoadCheck(strategy, applications(location, ContractOrigin.CONFIGURATION));

        // When: the mount's router is built
        Throwable failure = failureOf(vertx, check, applicationPublication(OpenApiContractValidationStrategy.ID));

        // Then: startup fails naming the application, the configuration setting, and the reason
        String message = assertValueFreeRefusal(failure, location);
        assertAll(
                unloadable.label() + ": " + message,
                () -> assertTrue(
                        message.contains("OpenAPI contract of application '" + APPLICATION + "' (jaxrs.applications."
                                + APPLICATION + ".openapiPath)" + CANNOT_BE_LOADED),
                        "names the application, its setting, and the strategy"),
                () -> assertTrue(message.contains(unloadable.reason()), "gives the reason: " + unloadable.reason()));
    }

    // ---------------------------------------------------------------------------------------------
    // Setting names
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(ContractOrigin.class)
    @DisplayName("the failure names the setting the application's contract location came from")
    void failureNamesTheSettingOfTheLocation(ContractOrigin origin, Vertx vertx) throws Exception {
        // Given: an application mount whose location, from the given origin, has a relative servers url
        Path location = write(MARKER + "-relative-servers.json", RELATIVE_SERVERS_CONTRACT);
        OpenApiContractValidationStrategy strategy = strategy(vertx, null);
        strategy.bindToMount(applicationMount(location));
        ContractLoadCheck check = new ContractLoadCheck(strategy, applications(location, origin));

        // When: the mount's router is built
        Throwable failure = failureOf(vertx, check, applicationPublication(OpenApiContractValidationStrategy.ID));

        // Then: the failure names the setting of that origin
        String setting =
                switch (origin) {
                    case CONFIGURATION -> "jaxrs.applications." + APPLICATION + ".openapiPath";
                    case ANNOTATION -> "the @RestApplication annotation's openapiPath";
                    case GLOBAL -> "jaxrs.openapiPath";
                };
        String message = assertValueFreeRefusal(failure, location);
        assertTrue(
                message.contains("OpenAPI contract of application '" + APPLICATION + "' (" + setting + ")"
                        + CANNOT_BE_LOADED + SERVERS_REASON),
                origin + ": names the setting " + setting + ": " + message);
    }

    @Test
    @DisplayName("an application absent from the declared-application view is named with the generic setting")
    void applicationAbsentFromTheViewIsNamedWithTheGenericSetting(Vertx vertx) throws Exception {
        // Given: an application mount bound to a relative-servers contract, and a view without it
        Path location = write(MARKER + "-relative-servers.json", RELATIVE_SERVERS_CONTRACT);
        OpenApiContractValidationStrategy strategy = strategy(vertx, null);
        strategy.bindToMount(applicationMount(location));
        ContractLoadCheck check = new ContractLoadCheck(strategy, new RestApplications(List.of()));

        // When: the mount's router is built
        Throwable failure = failureOf(vertx, check, applicationPublication(OpenApiContractValidationStrategy.ID));

        // Then: the failure names the application and the generic setting
        String message = assertValueFreeRefusal(failure, location);
        assertTrue(
                message.contains("OpenAPI contract of application '" + APPLICATION
                        + "' (the configuration, annotation, or global setting that supplied its location)"
                        + CANNOT_BE_LOADED + SERVERS_REASON),
                "names the generic setting: " + message);
    }

    @Test
    @DisplayName("a hand-built JAX-RS mount whose contract cannot be loaded is named by its mount path")
    void handBuiltMountIsNamedByItsMountPath(Vertx vertx) throws Exception {
        // Given: a hand-built mount bound to a relative-servers contract
        Path location = write(MARKER + "-relative-servers.json", RELATIVE_SERVERS_CONTRACT);
        OpenApiContractValidationStrategy strategy = strategy(vertx, null);
        strategy.bindToMount(new MountMeta(HAND_BUILT_MOUNT_ID, HAND_BUILT_MOUNT_PATH, location.toString(), Set.of()));
        ContractLoadCheck check = new ContractLoadCheck(strategy, new RestApplications(List.of()));

        // When: the mount's router is built
        Throwable failure = failureOf(
                vertx,
                check,
                new MountPublication(
                        HAND_BUILT_MOUNT_PATH,
                        HAND_BUILT_MOUNT_ID,
                        null,
                        null,
                        OpenApiContractValidationStrategy.ID,
                        List.of()));

        // Then: the failure names the mount path and the reason
        String message = assertValueFreeRefusal(failure, location);
        assertTrue(
                message.contains("OpenAPI contract of JAX-RS mount '" + HAND_BUILT_MOUNT_PATH + "'" + CANNOT_BE_LOADED
                        + SERVERS_REASON),
                "names the mount path: " + message);
    }

    /**
     * Two hand-built mounts at the same mount path share a mount id, and with several verticle instances
     * each binds; every contract bound under that id must load, whichever was bound last.
     */
    @ParameterizedTest(name = "unloadable contract bound first: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("a mount id bound to a loadable and an unloadable contract fails, in either bind order")
    void mountIdBoundToSeveralContractsFailsWhenAnyCannotBeLoaded(boolean unloadableFirst, Vertx vertx)
            throws Exception {
        // Given: two hand-built mounts with the same mount id and path, one bound to a loadable contract
        // and one to a contract with a relative servers url, bound in the given order
        Path loadable = write(MARKER + "-contract.json", VALID_CONTRACT);
        Path unloadable = write(MARKER + "-relative-servers.json", RELATIVE_SERVERS_CONTRACT);
        MountMeta loadableMount =
                new MountMeta(HAND_BUILT_MOUNT_ID, HAND_BUILT_MOUNT_PATH, loadable.toString(), Set.of());
        MountMeta unloadableMount =
                new MountMeta(HAND_BUILT_MOUNT_ID, HAND_BUILT_MOUNT_PATH, unloadable.toString(), Set.of());
        OpenApiContractValidationStrategy strategy = strategy(vertx, null);
        if (unloadableFirst) {
            strategy.bindToMount(unloadableMount);
            strategy.bindToMount(loadableMount);
        } else {
            strategy.bindToMount(loadableMount);
            strategy.bindToMount(unloadableMount);
        }
        ContractLoadCheck check = new ContractLoadCheck(strategy, new RestApplications(List.of()));

        // When: the mount's router is built
        Throwable failure = failureOf(vertx, check, handBuiltPublication());

        // Then: the failure names the mount path and the unloadable contract's reason, and echoes neither
        // location
        String message = assertValueFreeRefusal(failure, unloadable);
        assertAll(
                "unloadable bound first " + unloadableFirst + ": " + message,
                () -> assertTrue(
                        message.contains("OpenAPI contract of JAX-RS mount '" + HAND_BUILT_MOUNT_PATH + "'"
                                + CANNOT_BE_LOADED + SERVERS_REASON),
                        "names the mount path and the reason"),
                () -> assertFalse(message.contains(loadable.toString()), "echoes no loadable location"));
    }

    // ---------------------------------------------------------------------------------------------
    // Cases that must not fail
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"contract.json", "contract.JSON", "contract.yaml", "contract.YML"})
    @DisplayName("a loadable contract passes, its extension compared without regard to case")
    void loadableContractPasses(String fileName, Vertx vertx) throws Exception {
        // Given: an application mount bound to a loadable contract (JSON is valid YAML)
        Path location = write(MARKER + "-" + fileName, VALID_CONTRACT);
        OpenApiContractValidationStrategy strategy = strategy(vertx, null);
        strategy.bindToMount(applicationMount(location));
        ContractLoadCheck check = new ContractLoadCheck(strategy, applications(location, ContractOrigin.CONFIGURATION));

        // When: the mount's router is built
        Throwable failure = failureOf(vertx, check, applicationPublication(OpenApiContractValidationStrategy.ID));

        // Then: nothing fails
        assertNull(failure, () -> fileName + ": the check failed: " + failure);
    }

    @Test
    @DisplayName("a mount built under another strategy passes even when the strategy bound it to a broken contract")
    void otherStrategyPasses(Vertx vertx) throws Exception {
        // Given: a mount bound to a relative-servers contract, published under web-validation
        Path location = write(MARKER + "-relative-servers.json", RELATIVE_SERVERS_CONTRACT);
        OpenApiContractValidationStrategy strategy = strategy(vertx, null);
        strategy.bindToMount(applicationMount(location));
        ContractLoadCheck check = new ContractLoadCheck(strategy, applications(location, ContractOrigin.CONFIGURATION));

        // When: the mount's router is built
        Throwable failure = failureOf(vertx, check, applicationPublication("web-validation"));

        // Then: nothing fails
        assertNull(failure, () -> "the check failed: " + failure);
    }

    @Test
    @DisplayName("a mount the strategy never bound passes, and the unbound global contract does not fail it")
    void unboundMountPasses(Vertx vertx) throws Exception {
        // Given: a strategy whose broken global contract no mount binds, and no bound mount
        Path globalLocation = tempDir.resolve(MARKER + "-missing.json");
        OpenApiContractValidationStrategy strategy = strategy(vertx, globalLocation.toString());
        ContractLoadCheck check = new ContractLoadCheck(strategy, applications(globalLocation, ContractOrigin.GLOBAL));

        // When: a mount the strategy did not bind (an empty mount) is published under openapi-contract
        Throwable failure = failureOf(vertx, check, applicationPublication(OpenApiContractValidationStrategy.ID));

        // Then: nothing fails
        assertNull(failure, () -> "the check failed: " + failure);
    }

    @Test
    @DisplayName("a mount bound to a loadable contract passes while the global contract no mount binds is broken")
    void brokenUnboundGlobalContractDoesNotFailABoundMount(Vertx vertx) throws Exception {
        // Given: a broken global contract, and the application mount bound to its own loadable contract
        Path globalLocation = write(MARKER + "-global-relative-servers.json", RELATIVE_SERVERS_CONTRACT);
        Path location = write(MARKER + "-contract.json", VALID_CONTRACT);
        OpenApiContractValidationStrategy strategy = strategy(vertx, globalLocation.toString());
        strategy.bindToMount(applicationMount(location));
        ContractLoadCheck check = new ContractLoadCheck(strategy, applications(location, ContractOrigin.CONFIGURATION));

        // When: the mount's router is built
        Throwable failure = failureOf(vertx, check, applicationPublication(OpenApiContractValidationStrategy.ID));

        // Then: nothing fails
        assertNull(failure, () -> "the check failed: " + failure);
    }

    @Test
    @DisplayName("the check wants no operation detail")
    void wantsNoDetail(Vertx vertx) {
        ContractLoadCheck check = new ContractLoadCheck(strategy(vertx, null), new RestApplications(List.of()));
        assertAll(
                () -> assertFalse(check.wantsDetail(APPLICATION), "for an application"),
                () -> assertFalse(check.wantsDetail(null), "for a hand-built mount"));
    }

    // ---------------------------------------------------------------------------------------------
    // Completion context
    // ---------------------------------------------------------------------------------------------

    /**
     * The contract is loaded through another {@link Vertx} instance whose event loop is held, so the load
     * is still pending when the check is called on the caller's context; the returned future must then
     * complete there, not on the context that loaded the contract.
     */
    @Test
    @DisplayName("a check called while the contract still loads elsewhere completes on the caller's context")
    void pendingLoadCompletesOnTheCallersContext() throws Exception {
        Vertx loadingVertx = Vertx.vertx();
        Vertx callerVertx = Vertx.vertx();
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Given: the mount bound on the loading context, whose event loop is then held so the load
            // cannot complete
            Path location = write(MARKER + "-contract.json", VALID_CONTRACT);
            Context loadingContext = loadingVertx.getOrCreateContext();
            CompletableFuture<OpenApiContractValidationStrategy> bound = new CompletableFuture<>();
            loadingContext.runOnContext(ignored -> {
                try {
                    OpenApiContractValidationStrategy strategy = strategy(loadingVertx, null);
                    strategy.bindToMount(applicationMount(location));
                    bound.complete(strategy);
                    release.await(BOUND_SECONDS, TimeUnit.SECONDS);
                } catch (Throwable t) {
                    bound.completeExceptionally(t);
                }
            });
            OpenApiContractValidationStrategy strategy = bound.get(BOUND_SECONDS, TimeUnit.SECONDS);
            ContractLoadCheck check =
                    new ContractLoadCheck(strategy, applications(location, ContractOrigin.CONFIGURATION));

            // When: the check is called on the caller's context while the load is pending, then the
            // loading event loop is released
            Context callerContext = callerVertx.getOrCreateContext();
            CompletableFuture<Boolean> completeWhenReturned = new CompletableFuture<>();
            CompletableFuture<Context> completedOn = new CompletableFuture<>();
            callerContext.runOnContext(ignored -> {
                try {
                    Future<Void> result =
                            check.mountBuilt(applicationPublication(OpenApiContractValidationStrategy.ID));
                    completeWhenReturned.complete(result.isComplete());
                    result.onComplete(ar -> {
                        if (ar.succeeded()) {
                            completedOn.complete(Vertx.currentContext());
                        } else {
                            completedOn.completeExceptionally(ar.cause());
                        }
                    });
                } catch (Throwable t) {
                    completeWhenReturned.completeExceptionally(t);
                }
            });
            boolean wasComplete = completeWhenReturned.get(BOUND_SECONDS, TimeUnit.SECONDS);
            release.countDown();
            Context context = completedOn.get(BOUND_SECONDS, TimeUnit.SECONDS);

            // Then: the future was pending when returned, and completed successfully on the caller's context
            assertAll(
                    () -> assertFalse(wasComplete, "the returned future awaits the pending contract load"),
                    () -> assertSame(callerContext, context, "completes on the caller's context"),
                    () -> assertNotSame(loadingContext, context, "not on the context that loaded the contract"));
        } finally {
            release.countDown();
            Future.join(loadingVertx.close(), callerVertx.close())
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(BOUND_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("a check called after the contract loaded returns an already-succeeded future")
    void completedLoadReturnsACompletedFuture(Vertx vertx) throws Exception {
        // Given: a mount bound to a loadable contract whose load has completed
        Path location = write(MARKER + "-contract.json", VALID_CONTRACT);
        OpenApiContractValidationStrategy strategy = strategy(vertx, null);
        strategy.bindToMount(applicationMount(location));
        ContractLoadCheck check = new ContractLoadCheck(strategy, applications(location, ContractOrigin.CONFIGURATION));
        MountPublication publication = applicationPublication(OpenApiContractValidationStrategy.ID);
        assertNull(failureOf(vertx, check, publication), "the first check succeeds");

        // When: the check is called again on a context, and the returned future's state is read in the
        // same task, before anything else can run on that context
        CompletableFuture<Boolean> succeededWhenReturned = new CompletableFuture<>();
        vertx.getOrCreateContext().runOnContext(ignored -> {
            try {
                Future<Void> result = check.mountBuilt(publication);
                succeededWhenReturned.complete(result.isComplete() && result.succeeded());
            } catch (Throwable t) {
                succeededWhenReturned.completeExceptionally(t);
            }
        });

        // Then: the future it returned had already succeeded
        assertTrue(
                succeededWhenReturned.get(BOUND_SECONDS, TimeUnit.SECONDS),
                "the returned future is already succeeded when mountBuilt returns");
    }

    /**
     * Like {@link #pendingLoadCompletesOnTheCallersContext()}, but the pending contract cannot be loaded:
     * the returned future must then fail with the refusal on the caller's context.
     */
    @Test
    @DisplayName("a check called while an unloadable contract still loads elsewhere fails on the caller's context")
    void pendingFailingLoadFailsOnTheCallersContext() throws Exception {
        Vertx loadingVertx = Vertx.vertx();
        Vertx callerVertx = Vertx.vertx();
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Given: the mount bound on the loading context to a contract with a relative servers url,
            // whose event loop is then held so the load cannot complete
            Path location = write(MARKER + "-relative-servers.json", RELATIVE_SERVERS_CONTRACT);
            Context loadingContext = loadingVertx.getOrCreateContext();
            CompletableFuture<OpenApiContractValidationStrategy> bound = new CompletableFuture<>();
            loadingContext.runOnContext(ignored -> {
                try {
                    OpenApiContractValidationStrategy strategy = strategy(loadingVertx, null);
                    strategy.bindToMount(applicationMount(location));
                    bound.complete(strategy);
                    release.await(BOUND_SECONDS, TimeUnit.SECONDS);
                } catch (Throwable t) {
                    bound.completeExceptionally(t);
                }
            });
            OpenApiContractValidationStrategy strategy = bound.get(BOUND_SECONDS, TimeUnit.SECONDS);
            ContractLoadCheck check =
                    new ContractLoadCheck(strategy, applications(location, ContractOrigin.CONFIGURATION));

            // When: the check is called on the caller's context while the load is pending, then the
            // loading event loop is released
            Context callerContext = callerVertx.getOrCreateContext();
            CompletableFuture<Boolean> completeWhenReturned = new CompletableFuture<>();
            CompletableFuture<Context> failedOn = new CompletableFuture<>();
            CompletableFuture<Throwable> failure = new CompletableFuture<>();
            callerContext.runOnContext(ignored -> {
                try {
                    Future<Void> result =
                            check.mountBuilt(applicationPublication(OpenApiContractValidationStrategy.ID));
                    completeWhenReturned.complete(result.isComplete());
                    result.onComplete(ar -> {
                        if (ar.failed()) {
                            failedOn.complete(Vertx.currentContext());
                            failure.complete(ar.cause());
                        } else {
                            failedOn.completeExceptionally(new AssertionError("the check succeeded"));
                        }
                    });
                } catch (Throwable t) {
                    completeWhenReturned.completeExceptionally(t);
                }
            });
            boolean wasComplete = completeWhenReturned.get(BOUND_SECONDS, TimeUnit.SECONDS);
            release.countDown();
            Context context = failedOn.get(BOUND_SECONDS, TimeUnit.SECONDS);

            // Then: the future was pending when returned, and failed with the value-free refusal on the
            // caller's context
            String message = assertValueFreeRefusal(failure.get(BOUND_SECONDS, TimeUnit.SECONDS), location);
            assertAll(
                    () -> assertFalse(wasComplete, "the returned future awaits the pending contract load"),
                    () -> assertSame(callerContext, context, "fails on the caller's context"),
                    () -> assertNotSame(loadingContext, context, "not on the context that loaded the contract"),
                    () -> assertTrue(
                            message.contains("OpenAPI contract of application '" + APPLICATION
                                    + "' (jaxrs.applications." + APPLICATION + ".openapiPath)" + CANNOT_BE_LOADED
                                    + SERVERS_REASON),
                            "names the application, its setting, and the reason: " + message));
        } finally {
            release.countDown();
            Future.join(loadingVertx.close(), callerVertx.close())
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(BOUND_SECONDS, TimeUnit.SECONDS);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Asserts the failure is a cause-free {@link RestConfigurationException} whose message and string
     * form carry neither the marker, the location, nor its file name, and returns the message.
     */
    private static String assertValueFreeRefusal(Throwable failure, Path location) {
        assertNotNull(failure, "the check failed the mount");
        RestConfigurationException refusal =
                assertInstanceOf(RestConfigurationException.class, failure, () -> "the failure's class: " + failure);
        String message = refusal.getMessage();
        assertNotNull(message, "the failure has a message");
        String fileName = location.getFileName().toString();
        assertAll(
                "the failure: " + message,
                () -> assertNull(refusal.getCause(), "carries no cause"),
                () -> assertEquals(0, refusal.getSuppressed().length, "carries no suppressed exception"),
                () -> assertFalse(refusal.toString().contains(MARKER), "echoes no contract value"),
                () -> assertFalse(message.contains(location.toString()), "echoes no location"),
                () -> assertFalse(message.contains(fileName), "echoes no file name"));
        return message;
    }

    /** Creates what the case places at its location and returns the location. */
    private Path place(Unloadable unloadable) throws Exception {
        Path location = tempDir.resolve(unloadable.fileName());
        switch (unloadable.kind()) {
            case FILE -> Files.writeString(location, unloadable.content());
            case DIRECTORY -> Files.createDirectories(location);
            case ABSENT -> {
                // nothing is placed
            }
        }
        return location;
    }

    private Path write(String fileName, String content) throws Exception {
        return Files.writeString(tempDir.resolve(fileName), content);
    }

    /** Creates the strategy with the given global contract location ({@code null} pre-warms nothing). */
    private static OpenApiContractValidationStrategy strategy(Vertx vertx, String globalLocation) {
        return new OpenApiContractValidationStrategy(
                vertx, JaxRsConfig.builder().openapiPath(globalLocation).build());
    }

    private static MountMeta applicationMount(Path location) {
        return new MountMeta(APPLICATION_MOUNT_ID, APPLICATION_MOUNT_PATH, location.toString(), Set.of());
    }

    private static MountPublication applicationPublication(String strategyId) {
        return new MountPublication(
                APPLICATION_MOUNT_PATH, APPLICATION_MOUNT_ID, APPLICATION, OrdersApi.class, strategyId, List.of());
    }

    private static MountPublication handBuiltPublication() {
        return new MountPublication(
                HAND_BUILT_MOUNT_PATH,
                HAND_BUILT_MOUNT_ID,
                null,
                null,
                OpenApiContractValidationStrategy.ID,
                List.of());
    }

    private static RestApplications applications(Path location, ContractOrigin origin) {
        return new RestApplications(List.of(new RestApplications.Entry(
                APPLICATION, OrdersApi.class, true, APPLICATION_MOUNT_PATH, location.toString(), origin)));
    }

    /**
     * Calls the check on a context of {@code vertx} and returns its failure, or {@code null} when the
     * returned future succeeded. A synchronous throw fails the test: the check reports through its future.
     */
    private static Throwable failureOf(Vertx vertx, ContractLoadCheck check, MountPublication publication)
            throws Exception {
        CompletableFuture<Future<Void>> returned = new CompletableFuture<>();
        vertx.getOrCreateContext().runOnContext(ignored -> {
            try {
                returned.complete(check.mountBuilt(publication));
            } catch (Throwable t) {
                returned.completeExceptionally(t);
            }
        });
        Future<Void> result;
        try {
            result = returned.get(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException thrown) {
            return fail("mountBuilt threw instead of returning a failed future", thrown.getCause());
        }
        assertNotNull(result, "mountBuilt returned a future");
        try {
            result.toCompletionStage().toCompletableFuture().get(BOUND_SECONDS, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException failed) {
            return failed.getCause();
        }
    }
}
