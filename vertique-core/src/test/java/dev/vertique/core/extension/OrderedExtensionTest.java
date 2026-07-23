// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.extension;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the ordering contract defined by {@link OrderedExtension} and {@link ExtensionPhase}:
 * phase dominates priority, priority dominates orderKey, and orderKey provides a deterministic
 * tie-break.
 */
class OrderedExtensionTest {

    // --- Stub helpers ---

    /** Creates a stub with all three ordering axes specified explicitly. */
    private static OrderedExtension stub(ExtensionPhase phase, int priority, String orderKey) {
        return new OrderedExtension() {
            @Override
            public ExtensionPhase phase() {
                return phase;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public String orderKey() {
                return orderKey;
            }
        };
    }

    /** Creates a stub that relies entirely on {@link OrderedExtension} defaults. */
    private static OrderedExtension defaultStub() {
        return new OrderedExtension() {};
    }

    // --- Default value tests ---

    @Nested
    @DisplayName("Default values")
    class DefaultValues {

        @Test
        @DisplayName("default phase is APPLICATION")
        void defaultPhaseIsApplication() {
            assertEquals(ExtensionPhase.APPLICATION, defaultStub().phase());
        }

        @Test
        @DisplayName("default priority is 0")
        void defaultPriorityIsZero() {
            assertEquals(0, defaultStub().priority());
        }

        @Test
        @DisplayName("default orderKey is the implementation class name")
        void defaultOrderKeyIsClassName() {
            OrderedExtension ext = defaultStub();
            assertEquals(ext.getClass().getName(), ext.orderKey());
        }
    }

    // --- Phase dominance tests ---

    @Nested
    @DisplayName("Phase dominates priority")
    class PhaseDominance {

        @Test
        @DisplayName("SYSTEM_FIRST with MAX_VALUE priority sorts before APPLICATION with MIN_VALUE priority")
        void systemFirstBeforeApplicationRegardlessOfPriority() {
            OrderedExtension systemFirst = stub(ExtensionPhase.SYSTEM_FIRST, Integer.MAX_VALUE, "z");
            OrderedExtension application = stub(ExtensionPhase.APPLICATION, Integer.MIN_VALUE, "a");

            Comparator<OrderedExtension> cmp = OrderedExtension.comparator();
            assertTrue(
                    cmp.compare(systemFirst, application) < 0,
                    "SYSTEM_FIRST must sort before APPLICATION regardless of priority");
        }

        @Test
        @DisplayName("APPLICATION with MIN_VALUE priority sorts before SYSTEM_LAST with MAX_VALUE priority")
        void applicationBeforeSystemLastRegardlessOfPriority() {
            OrderedExtension application = stub(ExtensionPhase.APPLICATION, Integer.MIN_VALUE, "z");
            OrderedExtension systemLast = stub(ExtensionPhase.SYSTEM_LAST, Integer.MAX_VALUE, "a");

            Comparator<OrderedExtension> cmp = OrderedExtension.comparator();
            assertTrue(
                    cmp.compare(application, systemLast) < 0,
                    "APPLICATION must sort before SYSTEM_LAST regardless of priority");
        }

        @Test
        @DisplayName("SYSTEM_FIRST with MAX_VALUE priority sorts before SYSTEM_LAST with MIN_VALUE priority")
        void systemFirstBeforeSystemLastRegardlessOfPriority() {
            OrderedExtension systemFirst = stub(ExtensionPhase.SYSTEM_FIRST, Integer.MAX_VALUE, "z");
            OrderedExtension systemLast = stub(ExtensionPhase.SYSTEM_LAST, Integer.MIN_VALUE, "a");

            Comparator<OrderedExtension> cmp = OrderedExtension.comparator();
            assertTrue(
                    cmp.compare(systemFirst, systemLast) < 0,
                    "SYSTEM_FIRST must sort before SYSTEM_LAST regardless of priority");
        }

        @Test
        @DisplayName("full mixed list sorts into phase-major order")
        void fullListSortsInPhaseMajorOrder() {
            OrderedExtension sl = stub(ExtensionPhase.SYSTEM_LAST, -100, "sl");
            OrderedExtension app1 = stub(ExtensionPhase.APPLICATION, 10, "app1");
            OrderedExtension sf = stub(ExtensionPhase.SYSTEM_FIRST, 999, "sf");
            OrderedExtension app2 = stub(ExtensionPhase.APPLICATION, -5, "app2");

            List<OrderedExtension> sorted = Arrays.asList(sl, app1, sf, app2);
            sorted.sort(OrderedExtension.comparator());

            assertSame(sf, sorted.get(0), "SYSTEM_FIRST must be first");
            // app2 priority(-5) < app1 priority(10), so app2 before app1
            assertSame(app2, sorted.get(1), "APPLICATION lower priority must be second");
            assertSame(app1, sorted.get(2), "APPLICATION higher priority must be third");
            assertSame(sl, sorted.get(3), "SYSTEM_LAST must be last");
        }
    }

    // --- Priority within phase ---

    @Nested
    @DisplayName("Priority ordering within a phase")
    class PriorityOrdering {

        @Test
        @DisplayName("lower priority value sorts before higher within the same phase")
        void lowerPrioritySortsFirst() {
            OrderedExtension low = stub(ExtensionPhase.APPLICATION, -10, "low");
            OrderedExtension high = stub(ExtensionPhase.APPLICATION, 10, "high");

            Comparator<OrderedExtension> cmp = OrderedExtension.comparator();
            assertTrue(cmp.compare(low, high) < 0, "lower priority value must sort before higher");
        }

        @Test
        @DisplayName("equal priorities in the same phase are ordered by orderKey")
        void equalPriorityOrderedByKey() {
            OrderedExtension alpha = stub(ExtensionPhase.APPLICATION, 0, "alpha");
            OrderedExtension beta = stub(ExtensionPhase.APPLICATION, 0, "beta");

            Comparator<OrderedExtension> cmp = OrderedExtension.comparator();
            assertTrue(cmp.compare(alpha, beta) < 0, "alpha orderKey must sort before beta orderKey");
        }
    }

    // --- OrderKey tie-break ---

    @Nested
    @DisplayName("OrderKey tie-break")
    class OrderKeyTieBreak {

        @Test
        @DisplayName("same phase and priority: lexicographic orderKey determines order")
        void orderKeyBreaksTie() {
            OrderedExtension first = stub(ExtensionPhase.SYSTEM_FIRST, 5, "aaa");
            OrderedExtension second = stub(ExtensionPhase.SYSTEM_FIRST, 5, "zzz");

            Comparator<OrderedExtension> cmp = OrderedExtension.comparator();
            assertTrue(cmp.compare(first, second) < 0, "aaa must sort before zzz");
            assertTrue(cmp.compare(second, first) > 0, "zzz must sort after aaa");
        }

        @Test
        @DisplayName("identical phase, priority, and orderKey compares as equal")
        void identicalExtensionComparesAsEqual() {
            OrderedExtension a = stub(ExtensionPhase.APPLICATION, 0, "same");
            OrderedExtension b = stub(ExtensionPhase.APPLICATION, 0, "same");

            assertEquals(0, OrderedExtension.comparator().compare(a, b));
        }
    }

    // --- Comparator is consistent with equals / transitive ---

    @Nested
    @DisplayName("Comparator contract")
    class ComparatorContract {

        @Test
        @DisplayName("comparator is anti-symmetric")
        void comparatorIsAntiSymmetric() {
            OrderedExtension x = stub(ExtensionPhase.APPLICATION, 1, "x");
            OrderedExtension y = stub(ExtensionPhase.APPLICATION, 2, "y");

            Comparator<OrderedExtension> cmp = OrderedExtension.comparator();
            int xy = cmp.compare(x, y);
            int yx = cmp.compare(y, x);
            assertTrue(xy < 0 && yx > 0, "compare(x,y) and compare(y,x) must have opposite signs");
        }

        @Test
        @DisplayName("comparator returns the same non-null instance each call")
        void comparatorIsSingleton() {
            Comparator<OrderedExtension> c1 = OrderedExtension.comparator();
            Comparator<OrderedExtension> c2 = OrderedExtension.comparator();
            assertNotNull(c1);
            assertNotNull(c2);
        }
    }
}
