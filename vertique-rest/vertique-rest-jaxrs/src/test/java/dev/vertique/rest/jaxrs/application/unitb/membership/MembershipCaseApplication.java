// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * TP-005's (and TP-018's) reusable active overriding application fixture, registered by
 * {@link MembershipCaseApplicationRegistrationModule} with a hardcoded {@code active=true} (no
 * {@code @ConditionalOnProperty}, matching C-GEN's unconditional {@code true} literal). Every
 * membership-violation case (and TP-018's success case) that does not need its own dedicated
 * application class configures this fixture's {@link #classesSupplier} and
 * {@link #singletonsSupplier} before invoking composition, then restores the defaults via
 * {@link #reset()}.
 *
 * <p>Both {@link Application#getClasses()} and {@link Application#getSingletons()} are declared
 * (overridden) on this exact class, so C-COMPOSE step 1's overriding check
 * ({@code getDeclaredMethod} from {@code registration.type()} up to, but excluding,
 * {@code Application}) always classifies this registration as overriding, whatever the case.
 * {@link #getProperties()} is counted so TP-005 can assert C-COMPOSE step 7's
 * "{@code getProperties()} is never called" for every case.
 */
@ApplicationPath("/api/membership")
public class MembershipCaseApplication extends Application {

    /** This fixture's fixed, C-PATH-normalized registration path. */
    public static final String PATH = "/api/membership";

    /** Construction count; reset before every case via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** {@link #getProperties()} call count; reset before every case via {@link #reset()}. */
    public static final AtomicInteger GET_PROPERTIES_CALLS = new AtomicInteger();

    /** Drives {@link #getClasses()}; set by each case before composition, restored by {@link #reset()}. */
    public static volatile Supplier<Set<Class<?>>> classesSupplier = Set::of;

    /** Drives {@link #getSingletons()}; set by each case before composition, restored by {@link #reset()}. */
    public static volatile Supplier<Set<Object>> singletonsSupplier = Set::of;

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. Counts the construction. */
    public MembershipCaseApplication() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Returns the case-configured membership set.
     *
     * @return {@link #classesSupplier}'s current result
     */
    @Override
    public Set<Class<?>> getClasses() {
        return classesSupplier.get();
    }

    /**
     * Returns the case-configured singletons set.
     *
     * @return {@link #singletonsSupplier}'s current result
     */
    @Override
    public Set<Object> getSingletons() {
        return singletonsSupplier.get();
    }

    /**
     * Counts the call and returns an empty map; C-COMPOSE step 7 requires this is never invoked.
     *
     * @return an empty map
     */
    @Override
    public Map<String, Object> getProperties() {
        GET_PROPERTIES_CALLS.incrementAndGet();
        return Map.of();
    }

    /** Resets both counters and both suppliers to their defaults. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
        GET_PROPERTIES_CALLS.set(0);
        classesSupplier = Set::of;
        singletonsSupplier = Set::of;
    }
}
