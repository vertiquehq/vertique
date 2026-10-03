// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.MountPublicationHook;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Test {@link MountPublicationHook} that always succeeds and records, in call order, every
 * {@link #wantsDetail} argument it was asked and every {@link MountPublication} it was handed
 * (T006 TP-002, TP-003, TP-004, TP-005).
 *
 * <p>Whether an application wants detail is decided by a caller-supplied predicate, applied to the
 * (possibly {@code null}) application name {@link #wantsDetail} receives.
 */
public final class RecordingPublicationHook implements MountPublicationHook {

    private final Predicate<String> wantsDetail;
    private final List<String> wantsDetailArgs = new ArrayList<>();
    private final List<MountPublication> received = new ArrayList<>();

    /** Creates a hook that never wants detail for any mount. */
    public RecordingPublicationHook() {
        this(applicationName -> false);
    }

    /**
     * Creates a hook that wants detail exactly for the application names {@code wantsDetail}
     * accepts.
     *
     * @param wantsDetail the predicate deciding {@link #wantsDetail(String)}, applied to the
     *                    (possibly {@code null}) application name
     */
    public RecordingPublicationHook(Predicate<String> wantsDetail) {
        this.wantsDetail = wantsDetail;
    }

    @Override
    public boolean wantsDetail(String applicationName) {
        wantsDetailArgs.add(applicationName);
        return wantsDetail.test(applicationName);
    }

    @Override
    public Future<Void> mountBuilt(MountPublication publication) {
        received.add(publication);
        return Future.succeededFuture();
    }

    /**
     * Returns every {@link #wantsDetail(String)} argument received, in call order. Unlike
     * {@link List#copyOf}, this tolerates the {@code null} argument a non-application mount passes.
     *
     * @return an unmodifiable copy of the recorded arguments, which may contain {@code null}
     */
    public List<String> wantsDetailArgs() {
        return Collections.unmodifiableList(new ArrayList<>(wantsDetailArgs));
    }

    /**
     * Returns every {@link MountPublication} received, in call order.
     *
     * @return an immutable copy of the recorded publications
     */
    public List<MountPublication> received() {
        return List.copyOf(received);
    }

    /**
     * Returns the single recorded publication for the given mount path.
     *
     * @param mountPath the mount path to look up
     * @return the recorded publication for {@code mountPath}
     * @throws AssertionError if zero or more than one publication was recorded for {@code mountPath}
     */
    public MountPublication onlyReceivedFor(String mountPath) {
        List<MountPublication> matches =
                received.stream().filter(p -> p.mountPath().equals(mountPath)).toList();
        if (matches.size() != 1) {
            throw new AssertionError("expected exactly one publication for '" + mountPath + "', got: " + matches);
        }
        return matches.get(0);
    }

    /**
     * Returns the single recorded publication, when exactly one was recorded.
     *
     * @return the sole recorded publication
     * @throws AssertionError if zero or more than one publication was recorded
     */
    public MountPublication onlyReceived() {
        if (received.size() != 1) {
            throw new AssertionError("expected exactly one recorded publication, got: " + received);
        }
        return received.get(0);
    }
}
