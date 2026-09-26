// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import jakarta.inject.Inject;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * G-02 (a) and (b) fixture: an {@code @Inject}-constructed application whose constructor throws
 * whatever {@link #toThrow} names, so the composer's construction-wrapping rule (C-COMPOSE step 4,
 * "{@code create()}, {@code getClasses()}, or {@code getSingletons()}") is exercised for its
 * {@code create()} half, which TP-011 never covers (TP-011 only makes {@code getClasses()} throw).
 * Never itself constructed unless a case sets {@link #toThrow}; when it is {@code null}, the
 * constructor completes normally.
 */
@ApplicationPath("/api/throwing-ctor")
public class ThrowingConstructorApplication extends Application {

    /**
     * The throwable the constructor raises, or {@code null} to construct normally. Set by a case
     * before composition, restored by {@link #reset()}.
     */
    public static volatile Throwable toThrow;

    /**
     * Throws {@link #toThrow} when set, matching G-02's {@link RuntimeException} and
     * {@link NoClassDefFoundError} ({@link LinkageError}) cases.
     */
    @Inject
    public ThrowingConstructorApplication() {
        Throwable t = toThrow;
        if (t instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (t instanceof Error error) {
            throw error;
        }
    }

    /** Resets {@link #toThrow} to {@code null}. */
    public static void reset() {
        toThrow = null;
    }
}
