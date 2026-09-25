// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TP-005 case 16's declared application type: its registration's {@code type()} is this class, but
 * {@link MembershipMismatchedFactoryRegistrationModule}'s factory constructs and returns a
 * {@link MembershipWrongTypeApplication} instance instead, so C-COMPOSE step 4's type check
 * ({@code registration.type().isInstance(...)}) fails immediately, before {@link #getClasses()} or
 * {@link #getSingletons()} is ever read.
 */
@ApplicationPath("/api/mismatch")
public class MembershipDeclaredApplication extends Application {

    /** This fixture's fixed, C-PATH-normalized registration path. */
    public static final String PATH = "/api/mismatch";

    /** Construction count; reset before every case via {@link #reset()}. Never incremented for case 16. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** {@link #getProperties()} call count; reset before every case via {@link #reset()}. */
    public static final AtomicInteger GET_PROPERTIES_CALLS = new AtomicInteger();

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. Counts the construction. */
    public MembershipDeclaredApplication() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Declared purely so this registration classifies as overriding (C-COMPOSE step 1); never
     * reached for case 16, whose failure occurs at step 4's type check.
     *
     * @return an empty set
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of();
    }

    /**
     * Counts the call; never reached for case 16.
     *
     * @return an empty map
     */
    @Override
    public Map<String, Object> getProperties() {
        GET_PROPERTIES_CALLS.incrementAndGet();
        return Map.of();
    }

    /** Resets both counters to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
        GET_PROPERTIES_CALLS.set(0);
    }
}
