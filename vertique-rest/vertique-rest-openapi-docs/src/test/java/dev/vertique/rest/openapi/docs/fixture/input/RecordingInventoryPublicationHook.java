// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input;

import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.MountPublicationHook;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An {@link MountPublicationHook} that, during each call, projects every operation's binding
 * inventory into {@link InputProjection}s and keeps only those projections, per application name and
 * operation id. It retains nothing else from a publication.
 *
 * <p>It wants detail for every mount that serves a declared application, so the inventory is
 * built there whether or not the application has a document. Each call completes at once, on the
 * calling context. Several verticle instances building the same mount record equal projections; the
 * last one is kept.
 */
public final class RecordingInventoryPublicationHook implements MountPublicationHook {

    private final Map<Key, List<InputProjection>> inventories = new ConcurrentHashMap<>();

    /** Creates a hook with no recorded inventory. */
    public RecordingInventoryPublicationHook() {}

    /**
     * Wants detail for every mount that serves a declared application.
     *
     * @param applicationName the mount's application name, or {@code null}
     * @return {@code true} exactly when {@code applicationName} is non-null
     */
    @Override
    public boolean wantsDetail(@Nullable String applicationName) {
        return applicationName != null;
    }

    /**
     * Records the projected inventory of every operation that carries detail.
     *
     * @param publication the mount's publication
     * @return a succeeded future
     */
    @Override
    public Future<Void> mountBuilt(MountPublication publication) {
        String applicationName = publication.applicationName();
        if (applicationName != null) {
            for (OperationPublication operation : publication.operations()) {
                OperationDetail detail = operation.detail();
                if (detail != null) {
                    inventories.put(
                            new Key(applicationName, operation.operationId()), InputProjection.ofAll(detail.inputs()));
                }
            }
        }
        return Future.succeededFuture();
    }

    /**
     * Returns the projected inventory of one operation, in inventory order.
     *
     * @param applicationName the application name
     * @param operationId     the operation id
     * @return the projections
     * @throws IllegalStateException if no inventory was recorded for that operation
     */
    public List<InputProjection> inventory(String applicationName, String operationId) {
        List<InputProjection> inventory = inventories.get(new Key(applicationName, operationId));
        if (inventory == null) {
            throw new IllegalStateException("no inventory was recorded for operation '" + operationId
                    + "' of application '" + applicationName + "'; recorded: " + operationIds(applicationName));
        }
        return inventory;
    }

    /**
     * Returns the operation ids recorded for one application, sorted.
     *
     * @param applicationName the application name
     * @return the operation ids, empty when none was recorded
     */
    public Set<String> operationIds(String applicationName) {
        Set<String> ids = new TreeSet<>();
        for (Key key : inventories.keySet()) {
            if (key.applicationName().equals(applicationName)) {
                ids.add(key.operationId());
            }
        }
        return ids;
    }

    /** Identifies one operation of one application. */
    private record Key(String applicationName, String operationId) {}
}
