// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * G-02 (c) and (d) fixture: an overriding application whose {@link #getSingletons()} throws
 * whatever {@link #toThrow} names, so the composer's construction-wrapping rule (C-COMPOSE step 4)
 * is exercised for its {@code getSingletons()} half, which TP-011 never covers (TP-011 only makes
 * {@code getClasses()} throw). Declaring {@link #getSingletons()} classifies this registration as
 * overriding (C-COMPOSE step 1), which is irrelevant here because the throw happens before
 * membership is ever evaluated.
 */
@ApplicationPath("/api/throwing-singletons")
public class ThrowingGetSingletonsApplication extends Application {

    /**
     * The throwable {@link #getSingletons()} raises, or {@code null} to return an empty set. Set
     * by a case before composition, restored by {@link #reset()}.
     */
    public static volatile Throwable toThrow;

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public ThrowingGetSingletonsApplication() {}

    /**
     * Throws {@link #toThrow} when set, matching G-02's {@link RuntimeException} and
     * {@link NoClassDefFoundError} ({@link LinkageError}) cases; otherwise returns an empty set.
     *
     * @return an empty set, when {@link #toThrow} is {@code null}
     */
    @Override
    public Set<Object> getSingletons() {
        Throwable t = toThrow;
        if (t instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (t instanceof Error error) {
            throw error;
        }
        return Set.of();
    }

    /** Resets {@link #toThrow} to {@code null}. */
    public static void reset() {
        toThrow = null;
    }
}
