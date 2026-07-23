// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Behavior tests for {@link ObserverRegistry#match(Class)} subtype routing.
 *
 * <p>These tests are RED in slice 3.1: {@link ObserverRegistry#match(Class)} is a failing stub that
 * throws {@link UnsupportedOperationException}. The green step (slice 3.1 implementation) supplies the
 * superclass-chain plus interface walk and turns them green.
 */
class SubtypeRoutingTest {

    /** A supertype event used to register a supertype observer. */
    private static class Animal {}

    /** A subtype event fired against a {@code Animal} observer. */
    private static final class Dog extends Animal {}

    /** An interface event used to register an interface observer. */
    private interface Flyable {}

    /** A subtype event implementing {@code Flyable}. */
    private static final class Bird implements Flyable {}

    /**
     * Given a registry holding one {@code Animal} observer and one {@code Flyable} observer, when
     * {@code match} is called with a fired subtype, then the supertype/interface observer is returned
     * (an observer of a supertype receives a fired subtype, over both the superclass chain and
     * interfaces).
     */
    @Test
    void supertypeObserverReceivesFiredSubtype() {
        ObserverRegistration animalReg = new ObserverRegistration(Animal.class, 1000, event -> {});
        ObserverRegistration flyableReg = new ObserverRegistration(Flyable.class, 1000, event -> {});
        ObserverRegistry registry = new ObserverRegistry(Set.of(animalReg, flyableReg));

        List<ObserverRegistration> dogMatches = registry.match(Dog.class);
        assertEquals(1, dogMatches.size(), "Dog should route to the Animal supertype observer");
        assertTrue(dogMatches.contains(animalReg), "the Animal observer should match a fired Dog");

        List<ObserverRegistration> birdMatches = registry.match(Bird.class);
        assertEquals(1, birdMatches.size(), "Bird should route to the Flyable interface observer");
        assertTrue(birdMatches.contains(flyableReg), "the Flyable observer should match a fired Bird");
    }

    /**
     * Given a registry holding one {@code Animal} observer, when {@code match} is called with an
     * unrelated type, then the returned list is empty.
     */
    @Test
    void noMatchForUnrelatedType() {
        ObserverRegistration animalReg = new ObserverRegistration(Animal.class, 1000, event -> {});
        ObserverRegistry registry = new ObserverRegistry(Set.of(animalReg));

        List<ObserverRegistration> matches = registry.match(String.class);
        assertTrue(matches.isEmpty(), "an unrelated fired type should match no observers");
    }
}
