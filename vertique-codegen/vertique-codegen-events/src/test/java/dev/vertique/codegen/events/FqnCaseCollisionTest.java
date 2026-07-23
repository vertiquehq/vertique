// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.events;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link EventsProcessor} generates distinct {@code @Binds} method names in
 * {@code GeneratedEventsModule} for event-type FQNs that could otherwise collide under a string-
 * encoding scheme:
 *
 * <ul>
 *   <li><strong>P3-W6 case collision</strong> — FQNs differing only in the case of a
 *       segment-initial character (e.g. {@code com.foo.Bar} vs {@code com.Foo.Bar}).</li>
 *   <li><strong>Underscore/dot-placement collision</strong> — FQNs whose string value is identical
 *       after naively replacing {@code .} with {@code _}, because one has a literal {@code _} in a
 *       segment name while the other uses a {@code .} separator at the same position (e.g.
 *       {@code com.foo_Bar.Evt} vs {@code com.foo.Bar_Evt}).</li>
 *   <li><strong>Adjacent-boundary collision</strong> — FQNs that differ only in whether the
 *       {@code _} is in the last package segment or the first class-name character, e.g.
 *       {@code com.foo_.Evt} vs {@code com.foo._Evt}.</li>
 * </ul>
 *
 * <p>The ordinal-based method naming in {@code EventsProcessor} — using the event type's
 * position in the inventory sorted by FQN ({@link String} natural order) as the unique
 * discriminator — is provably injective: distinct ordinals always produce distinct method names,
 * regardless of how the FQNs relate. Sorting by FQN also makes the ordinals deterministic across
 * compilation runs, independent of {@code RoundEnvironment.getRootElements()} iteration order
 * (P3-W7). These tests verify the load-bearing property: compilation SUCCEEDS (no duplicate
 * method) and both type references appear in the generated module source.
 */
class FqnCaseCollisionTest {

    // --- P3-W6 case-collision fixtures ---

    // Module package = LCP of "com.foo" and "com.Foo" = "com"
    private static final String CASE_COLLISION_MODULE_FQN = "com.GeneratedEventsModule";

    /** Event type {@code com.foo.Bar} — package {@code com.foo}. */
    private static JavaFileObject lowerFooBar() {
        return SourceFiles.inline("com.foo.Bar", """
                        package com.foo;
                        public class Bar {
                            public Bar() {}
                        }
                        """);
    }

    /**
     * Event type {@code com.Foo.Bar} — package {@code com.Foo}.
     * The FQN differs from {@link #lowerFooBar()} only in the case of the second segment.
     */
    private static JavaFileObject upperFooBar() {
        return SourceFiles.inline("com.Foo.Bar", """
                        package com.Foo;
                        public class Bar {
                            public Bar() {}
                        }
                        """);
    }

    /** A bean that injects both publishers, ensuring both types enter the inventory. */
    private static JavaFileObject caseCollisionPublishingBean() {
        return SourceFiles.inline("com.example.BarPublisher", """
                        package com.example;
                        import dev.vertique.events.Event;
                        import jakarta.inject.Inject;
                        public class BarPublisher {
                            private final Event<com.foo.Bar> lower;
                            private final Event<com.Foo.Bar> upper;
                            @Inject
                            public BarPublisher(Event<com.foo.Bar> lower, Event<com.Foo.Bar> upper) {
                                this.lower = lower;
                                this.upper = upper;
                            }
                        }
                        """);
    }

    // --- Underscore/dot-placement collision fixtures ---

    // Module package = LCP of "com.foo_Bar" and "com.foo" = "com"
    private static final String UNDERSCORE_COLLISION_MODULE_FQN = "com.GeneratedEventsModule";

    /**
     * Event type {@code com.foo_Bar.Evt} — package {@code com.foo_Bar}, class {@code Evt}.
     * Package segment contains a literal {@code _}; FQN is {@code com.foo_Bar.Evt}.
     */
    private static JavaFileObject fooUnderscoreBarEvt() {
        return SourceFiles.inline("com.foo_Bar.Evt", """
                        package com.foo_Bar;
                        public class Evt {
                            public Evt() {}
                        }
                        """);
    }

    /**
     * Event type {@code com.foo.Bar_Evt} — package {@code com.foo}, class {@code Bar_Evt}.
     * Class name contains a literal {@code _}; FQN is {@code com.foo.Bar_Evt}.
     * Without ordinal-based naming, naively replacing '.' with '_' maps both to
     * {@code com_foo_Bar_Evt}, causing a collision.
     */
    private static JavaFileObject fooBarUnderscoreEvt() {
        return SourceFiles.inline("com.foo.Bar_Evt", """
                        package com.foo;
                        public class Bar_Evt {
                            public Bar_Evt() {}
                        }
                        """);
    }

    /**
     * A bean that injects both underscore-collision event publishers, ensuring both types enter the
     * processor inventory.
     */
    private static JavaFileObject underscoreCollisionPublishingBean() {
        return SourceFiles.inline("com.example.EvtPublisher", """
                        package com.example;
                        import dev.vertique.events.Event;
                        import jakarta.inject.Inject;
                        public class EvtPublisher {
                            private final Event<com.foo_Bar.Evt> withUnderscoreInPackage;
                            private final Event<com.foo.Bar_Evt> withUnderscoreInClass;
                            @Inject
                            public EvtPublisher(
                                    Event<com.foo_Bar.Evt> withUnderscoreInPackage,
                                    Event<com.foo.Bar_Evt> withUnderscoreInClass) {
                                this.withUnderscoreInPackage = withUnderscoreInPackage;
                                this.withUnderscoreInClass = withUnderscoreInClass;
                            }
                        }
                        """);
    }

    // --- Adjacent-boundary collision fixtures ---

    // com.foo_.Evt  — underscore at end of package segment
    // com.foo._Evt  — underscore at start of class name
    // Module package = LCP of "com.foo_" and "com.foo" = "com"
    private static final String ADJACENT_BOUNDARY_MODULE_FQN = "com.GeneratedEventsModule";

    /**
     * Event type {@code com.foo_.Evt} — package {@code com.foo_}, class {@code Evt}.
     * The trailing {@code _} is in the package segment.
     */
    private static JavaFileObject fooTrailingUnderscoreEvt() {
        return SourceFiles.inline("com.foo_.Evt", """
                        package com.foo_;
                        public class Evt {
                            public Evt() {}
                        }
                        """);
    }

    /**
     * Event type {@code com.foo._Evt} — package {@code com.foo}, class {@code _Evt}.
     * The leading {@code _} is in the class name.
     */
    private static JavaFileObject fooLeadingUnderscoreEvt() {
        return SourceFiles.inline("com.foo._Evt", """
                        package com.foo;
                        public class _Evt {
                            public _Evt() {}
                        }
                        """);
    }

    /**
     * A bean that injects both adjacent-boundary event publishers, ensuring both types enter the
     * processor inventory.
     */
    private static JavaFileObject adjacentBoundaryPublishingBean() {
        return SourceFiles.inline("com.example.BoundaryPublisher", """
                        package com.example;
                        import dev.vertique.events.Event;
                        import jakarta.inject.Inject;
                        public class BoundaryPublisher {
                            private final Event<com.foo_.Evt> trailingUnderscore;
                            private final Event<com.foo._Evt> leadingUnderscore;
                            @Inject
                            public BoundaryPublisher(
                                    Event<com.foo_.Evt> trailingUnderscore,
                                    Event<com.foo._Evt> leadingUnderscore) {
                                this.trailingUnderscore = trailingUnderscore;
                                this.leadingUnderscore = leadingUnderscore;
                            }
                        }
                        """);
    }

    // --- P3-W6 case-collision tests ---

    @Nested
    @DisplayName("P3-W6: FQNs differing only in segment-initial case")
    class CaseCollisionTests {

        @Test
        @DisplayName("two event types differing only in FQN segment case compile — distinct @Binds method names")
        void fqnCaseDifferenceProducesDistinctBindsMethodNames() {
            // Given: com.foo.Bar and com.Foo.Bar (FQNs differ only in case of 2nd segment)
            // When: EventsProcessor runs
            // Then: compilation SUCCEEDS — the ordinal-based method naming produces distinct @Binds
            //       method names (bind<SimpleName>_<ordinal>Event); both type references appear in
            //       the generated module source
            ProcessorTestHarness.run(new EventsProcessor(), lowerFooBar(), upperFooBar(), caseCollisionPublishingBean())
                    .assertSuccess()
                    .assertGeneratedSourceContains(CASE_COLLISION_MODULE_FQN, "com.foo.Bar")
                    .assertGeneratedSourceContains(CASE_COLLISION_MODULE_FQN, "com.Foo.Bar");
        }

        @Test
        @DisplayName("generated @Binds method names for case-differing FQNs are distinct (ordinal-based)")
        void fqnCaseDifferenceYieldsDistinctOrdinalMethodNames() {
            // Given: com.foo.Bar and com.Foo.Bar
            // When: EventsProcessor runs
            // Then: the generated module source contains two distinct ordinal-based @Binds method names;
            //       the ordinal suffix (_0, _1) guarantees uniqueness regardless of simple-name clash
            ProcessorTestHarness.run(new EventsProcessor(), lowerFooBar(), upperFooBar(), caseCollisionPublishingBean())
                    .assertSuccess()
                    .assertGeneratedSourceContains(CASE_COLLISION_MODULE_FQN, "bindBar_0Event")
                    .assertGeneratedSourceContains(CASE_COLLISION_MODULE_FQN, "bindBar_1Event");
        }
    }

    // --- Underscore/dot-placement collision tests ---

    @Nested
    @DisplayName("Underscore/dot-placement: FQNs colliding under naive replace('.','_')")
    class UnderscoreDotPlacementCollisionTests {

        @Test
        @DisplayName(
                "com.foo_Bar.Evt and com.foo.Bar_Evt compile — ordinal-based naming yields distinct @Binds method names")
        void underscoreDotPlacementDifferenceProducesDistinctBindsMethodNames() {
            // Given: com.foo_Bar.Evt (literal _ in package segment) and com.foo.Bar_Evt (literal _ in
            //        class name); naively replacing '.' with '_' maps both to "com_foo_Bar_Evt"
            // When: EventsProcessor runs with ordinal-based method naming
            // Then: compilation SUCCEEDS — distinct @Binds method names are generated using the
            //       event type's ordinal; both type references appear in the generated module source
            ProcessorTestHarness.run(
                            new EventsProcessor(),
                            fooUnderscoreBarEvt(),
                            fooBarUnderscoreEvt(),
                            underscoreCollisionPublishingBean())
                    .assertSuccess()
                    .assertGeneratedSourceContains(UNDERSCORE_COLLISION_MODULE_FQN, "com.foo_Bar.Evt")
                    .assertGeneratedSourceContains(UNDERSCORE_COLLISION_MODULE_FQN, "com.foo.Bar_Evt");
        }

        @Test
        @DisplayName("ordinal-based naming yields distinct @Binds method names for underscore/dot-placement FQNs")
        void underscoreDotPlacementYieldsDistinctOrdinalMethodNames() {
            // Given: com.foo_Bar.Evt (simple name = Evt) and com.foo.Bar_Evt (simple name = Bar_Evt)
            // When: EventsProcessor runs
            // Then: the generated module source contains two distinct ordinal-based @Binds method names.
            //       FQN sort order: '.' (46) < '_' (95), so "com.foo.Bar_Evt" < "com.foo_Bar.Evt".
            //       Ordinal 0 → com.foo.Bar_Evt (simple name Bar_Evt) → bindBar_Evt_0Event
            //       Ordinal 1 → com.foo_Bar.Evt (simple name Evt)     → bindEvt_1Event
            ProcessorTestHarness.run(
                            new EventsProcessor(),
                            fooUnderscoreBarEvt(),
                            fooBarUnderscoreEvt(),
                            underscoreCollisionPublishingBean())
                    .assertSuccess()
                    .assertGeneratedSourceContains(UNDERSCORE_COLLISION_MODULE_FQN, "bindBar_Evt_0Event")
                    .assertGeneratedSourceContains(UNDERSCORE_COLLISION_MODULE_FQN, "bindEvt_1Event");
        }
    }

    // --- Adjacent-boundary collision tests ---

    @Nested
    @DisplayName("Adjacent-boundary: _ at end of package segment vs start of class name")
    class AdjacentBoundaryCollisionTests {

        @Test
        @DisplayName("com.foo_.Evt and com.foo._Evt compile — ordinal-based naming yields distinct @Binds method names")
        void adjacentBoundaryDifferenceProducesDistinctBindsMethodNames() {
            // Given: com.foo_.Evt (trailing _ in package) and com.foo._Evt (leading _ in class name)
            //        these two FQNs are adjacent at the package/class boundary with an underscore
            // When: EventsProcessor runs with ordinal-based method naming
            // Then: compilation SUCCEEDS — distinct @Binds method names are generated using the
            //       event type's ordinal; both type references appear in the generated module source
            ProcessorTestHarness.run(
                            new EventsProcessor(),
                            fooTrailingUnderscoreEvt(),
                            fooLeadingUnderscoreEvt(),
                            adjacentBoundaryPublishingBean())
                    .assertSuccess()
                    .assertGeneratedSourceContains(ADJACENT_BOUNDARY_MODULE_FQN, "com.foo_.Evt")
                    .assertGeneratedSourceContains(ADJACENT_BOUNDARY_MODULE_FQN, "com.foo._Evt");
        }

        @Test
        @DisplayName("ordinal-based naming yields distinct @Binds method names for adjacent-boundary FQNs")
        void adjacentBoundaryYieldsDistinctOrdinalMethodNames() {
            // Given: com.foo_.Evt (simple name = Evt) and com.foo._Evt (simple name = _Evt)
            // When: EventsProcessor runs
            // Then: the generated module source contains two distinct ordinal-based @Binds method names.
            //       FQN sort order: '.' (46) < '_' (95), so "com.foo._Evt" < "com.foo_.Evt".
            //       Ordinal 0 → com.foo._Evt (simple name _Evt) → bind_Evt_0Event
            //       Ordinal 1 → com.foo_.Evt (simple name Evt)  → bindEvt_1Event
            ProcessorTestHarness.run(
                            new EventsProcessor(),
                            fooTrailingUnderscoreEvt(),
                            fooLeadingUnderscoreEvt(),
                            adjacentBoundaryPublishingBean())
                    .assertSuccess()
                    .assertGeneratedSourceContains(ADJACENT_BOUNDARY_MODULE_FQN, "bind_Evt_0Event")
                    .assertGeneratedSourceContains(ADJACENT_BOUNDARY_MODULE_FQN, "bindEvt_1Event");
        }
    }
}
