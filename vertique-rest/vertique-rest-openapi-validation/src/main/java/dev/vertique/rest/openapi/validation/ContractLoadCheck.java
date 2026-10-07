// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.validation;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.MountPublicationHook;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.file.FileSystemException;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;

/**
 * Fails a mount's router creation when the contract the {@code openapi-contract} strategy validates
 * that mount against cannot be loaded, so an unloadable contract fails startup instead of answering
 * HTTP 500 at the first validated request.
 *
 * <p>For a mount published under the {@code openapi-contract} strategy, the check looks up every contract
 * path {@link OpenApiContractValidationStrategy#bindToMount} recorded under the mount's id — several when
 * mounts sharing that id were bound to different contracts — and awaits each contract's cached load. A
 * mount published under another strategy, or one the strategy never bound (an empty mount), passes.
 * Every location's extension is checked first: it must be {@code json}, {@code yaml}, or {@code yml},
 * compared without regard to case. Paths are visited in ascending order, so when several contracts are
 * at fault the refusal deterministically reports the first.
 *
 * <p>A failure is a {@link RestConfigurationException} without a cause. Its message names the
 * application and the setting its contract location came from — or, for a mount serving no declared
 * application, the mount path — and the reason class, derived from the load failure's type: an
 * unsupported extension, an unreadable file, a {@code servers} URL that is not a valid URL, or
 * a document that is not a valid OpenAPI contract. It never carries the location, the file's content,
 * or any parser text; the strategy logs the underlying cause at {@code WARN} for the operator.
 *
 * <p>The returned future is already complete when every load has finished. While any load is still
 * pending, it completes on the caller's Vert.x context, never on the context that loads a contract.
 */
final class ContractLoadCheck implements MountPublicationHook {

    /** The fixed part of every failure message, after the mount's identification. */
    private static final String CANNOT_BE_LOADED =
            " cannot be loaded for request-validation strategy '" + OpenApiContractValidationStrategy.ID + "': ";

    /** The contract location extensions vertx-openapi loads, in lower case. */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("json", "yaml", "yml");

    /** The reason for a location whose extension is not supported. */
    private static final String EXTENSION_REASON = "its location must end in .json, .yaml, or .yml";

    /** The reason for a contract file that cannot be read. */
    private static final String UNREADABLE_REASON = "the file cannot be read";

    /** The reason for a contract whose {@code servers} URL is not a valid URL. */
    private static final String SERVERS_REASON = "a servers url is not a valid URL; use a valid URL or omit servers";

    /** The reason for any other contract vertx-openapi rejects. */
    private static final String INVALID_REASON = "the file is not a valid OpenAPI contract";

    /** The setting named when the application is absent from the declared-application view. */
    private static final String UNKNOWN_SETTING =
            "the configuration, annotation, or global setting that supplied its location";

    /** How deep a load failure's cause chain is walked, bounding a cyclic chain. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private final OpenApiContractValidationStrategy strategy;
    private final RestApplications applications;

    /**
     * Creates the check.
     *
     * @param strategy     the strategy whose per-mount contract loads are awaited
     * @param applications the declared applications, used to name the setting a location came from
     */
    @Inject
    ContractLoadCheck(OpenApiContractValidationStrategy strategy, RestApplications applications) {
        this.strategy = strategy;
        this.applications = applications;
    }

    /**
     * Reports {@code false}: the check needs no operation detail, only the mount's identity.
     *
     * @param applicationName the application name, or {@code null} for a mount serving none
     * @return {@code false}, always
     */
    @Override
    public boolean wantsDetail(@Nullable String applicationName) {
        return false;
    }

    /**
     * Awaits the load of every contract bound under the mount's id by the {@code openapi-contract}
     * strategy.
     *
     * <p>The bound paths are visited in ascending order. The first path whose extension is unsupported
     * fails the check before any load is consulted. Otherwise the check waits until every load has
     * completed and, when any failed, refuses with the reason of the first failed load in that order.
     *
     * @param publication the mount's publication; not retained past this call
     * @return a succeeded future when the mount is not validated by that strategy, was not bound by it,
     *     or every contract bound under its id loaded; otherwise a future failed with a value-free
     *     {@link RestConfigurationException}, or with an {@link IllegalStateException} naming the mount id
     *     when a bound path has no cached load
     */
    @Override
    public Future<Void> mountBuilt(MountPublication publication) {
        if (!OpenApiContractValidationStrategy.ID.equals(publication.strategyId())) {
            return Future.succeededFuture();
        }
        SortedSet<String> paths = strategy.boundContractPaths(publication.mountId());
        if (paths.isEmpty()) {
            return Future.succeededFuture();
        }
        String subject = subject(publication.applicationName(), publication.mountPath());
        for (String path : paths) {
            if (!SUPPORTED_EXTENSIONS.contains(extension(path))) {
                return Future.failedFuture(refusal(subject, EXTENSION_REASON));
            }
        }
        List<Future<?>> loads = new ArrayList<>(paths.size());
        boolean allComplete = true;
        for (String path : paths) {
            Future<?> load = strategy.contractLoad(path);
            if (load == null) {
                return Future.failedFuture(new IllegalStateException("openapi-contract: mount '"
                        + publication.mountId() + "' was bound to a contract with no cached load; bindToMount "
                        + "must cache a mount's contract load before its router is built."));
            }
            loads.add(load);
            allComplete &= load.isComplete();
        }
        if (allComplete) {
            return outcome(loads, subject);
        }
        Future<?> all = Future.join(loads);
        Context context = Vertx.currentContext();
        if (context == null) {
            return all.transform(ignored -> outcome(loads, subject));
        }
        Promise<Void> promise = Promise.promise();
        all.onComplete(
                ignored -> context.runOnContext(none -> outcome(loads, subject).onComplete(promise)));
        return promise.future();
    }

    /**
     * Maps completed contract loads to this check's outcome.
     *
     * @param loads   the completed loads, in ascending order of their contract paths
     * @param subject the mount's identification for the failure message
     * @return a succeeded future when every load succeeded, or one failed with the value-free refusal
     *     classified from the first failed load
     */
    private static Future<Void> outcome(List<Future<?>> loads, String subject) {
        for (Future<?> load : loads) {
            if (load.failed()) {
                return Future.failedFuture(refusal(subject, reason(load.cause())));
            }
        }
        return Future.succeededFuture();
    }

    /**
     * Identifies the mount in the failure message: by application name and the setting its contract
     * location came from, or by mount path for a mount serving no declared application.
     *
     * @param applicationName the declared application's name, or {@code null}
     * @param mountPath       the mount path as registered
     * @return the message's subject
     */
    private String subject(@Nullable String applicationName, String mountPath) {
        if (applicationName == null) {
            return "OpenAPI contract of JAX-RS mount '" + mountPath + "'";
        }
        String setting = applications
                .byName(applicationName)
                .map(entry -> switch (entry.contractOrigin()) {
                    case CONFIGURATION -> "jaxrs.applications." + applicationName + ".openapiPath";
                    case ANNOTATION -> "the @RestApplication annotation's openapiPath";
                    case GLOBAL -> "jaxrs.openapiPath";
                })
                .orElse(UNKNOWN_SETTING);
        return "OpenAPI contract of application '" + applicationName + "' (" + setting + ")";
    }

    /**
     * Returns the lower-case extension of a contract location: the text after its last {@code '.'}, or
     * the whole location when it has none.
     *
     * @param path the contract location
     * @return the lower-case extension
     */
    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return (dot < 0 ? path : path.substring(dot + 1)).toLowerCase(Locale.ROOT);
    }

    /**
     * Classifies a contract-load failure by the types in its cause chain, never by its message.
     *
     * @param failure the load failure
     * @return the reason class for the failure message
     */
    private static String reason(Throwable failure) {
        boolean unreadable = false;
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof URISyntaxException || t instanceof MalformedURLException) {
                return SERVERS_REASON;
            }
            if (t instanceof FileSystemException) {
                unreadable = true;
            }
        }
        return unreadable ? UNREADABLE_REASON : INVALID_REASON;
    }

    /**
     * Creates the cause-free refusal.
     *
     * @param subject the mount's identification
     * @param reason  the reason class
     * @return the refusal
     */
    private static RestConfigurationException refusal(String subject, String reason) {
        return new RestConfigurationException(subject + CANNOT_BE_LOADED + reason);
    }
}
