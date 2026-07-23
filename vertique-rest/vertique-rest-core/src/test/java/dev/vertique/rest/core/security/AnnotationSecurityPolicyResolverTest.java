// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.util.AnnotationResolver;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AnnotationSecurityPolicyResolver} covering both the reflective
 * {@code resolve}/{@code hasConflictingAnnotations}/{@code describeConflict}/
 * {@code hasEmptyRolesAllowed} entry points and the {@code FromAnnotations} variants that
 * accept pre-resolved annotation lists. The reflective entry points are also the surface
 * exercised by the bare {@link SecurityPolicyResolver} interface defaults.
 *
 * <p>Critical scenarios pinned:
 *
 * <ul>
 *   <li>{@link SecurityPolicy} mapping for each annotation kind ({@code @DenyAll},
 *       {@code @PermitAll}, {@code @RolesAllowed}, {@code @Authorized} alone vs combined
 *       with {@code @RolesAllowed}, scopes only, scopes+matchAll, scopes+roles).</li>
 *   <li>Method-level overrides class-level (Jakarta EE override semantics).</li>
 *   <li>Conflict detection at method-level only, class-level only, and both levels.</li>
 *   <li>{@code describeConflict} reports the level where the conflict actually lives.</li>
 *   <li>Empty-value {@code @RolesAllowed} detected at method and class level with
 *       Jakarta EE override applied.</li>
 *   <li>Interface-declared annotations honoured via {@link AnnotationResolver}.</li>
 * </ul>
 */
class AnnotationSecurityPolicyResolverTest {

    private final AnnotationSecurityPolicyResolver resolver = new AnnotationSecurityPolicyResolver();

    // --- Fixtures: classes carrying various annotations ---

    @PermitAll
    static class ClassPermitAll {
        public void m() {}

        @DenyAll
        public void mDeny() {}

        @RolesAllowed("admin")
        public void mRoles() {}

        @Authorized
        public void mAuth() {}
    }

    @RolesAllowed("user")
    static class ClassRoles {
        public void m() {}
    }

    @Authorized(
            scopes = {"read", "write"},
            matchAll = true)
    static class ClassAuthScopes {
        public void m() {}
    }

    @RolesAllowed({})
    static class ClassEmptyRoles {
        public void m() {}
    }

    static class NoAnnotations {
        public void m() {}

        @DenyAll
        public void deny() {}

        @PermitAll
        public void permit() {}

        @RolesAllowed("admin")
        public void roles() {}

        @RolesAllowed({})
        public void emptyRoles() {}

        @Authorized
        public void authNoScope() {}

        @Authorized(scopes = "read")
        public void authScopes() {}

        @Authorized(
                scopes = {"read", "write"},
                matchAll = true)
        public void authScopesMatchAll() {}

        @RolesAllowed("admin")
        @Authorized(scopes = "read")
        public void rolesPlusAuth() {}

        @DenyAll
        @PermitAll
        public void conflictMethod() {}

        @PermitAll
        @RolesAllowed("admin")
        public void conflictMethod2() {}
    }

    // --- Interface-backed fixtures (exercise AnnotationResolver interface walk) ---

    interface ProtectedApi {
        @RolesAllowed("admin")
        void op();
    }

    static class ProtectedImpl implements ProtectedApi {
        @Override
        public void op() {}
    }

    @PermitAll
    interface PublicApi {
        void op();
    }

    static class PublicImpl implements PublicApi {
        @Override
        public void op() {}
    }

    // --- SecurityPolicy mapping ---

    @Nested
    @DisplayName("resolve — produces correct SecurityPolicy variant for each annotation set")
    class ResolveMapping {

        @Test
        @DisplayName("@DenyAll on method → SecurityPolicy.DenyAll")
        void denyAll() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(NoAnnotations.class.getMethod("deny"), NoAnnotations.class);
            assertInstanceOf(SecurityPolicy.DenyAll.class, p);
        }

        @Test
        @DisplayName("@PermitAll on method → SecurityPolicy.PermitAll")
        void permitAll() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(NoAnnotations.class.getMethod("permit"), NoAnnotations.class);
            assertInstanceOf(SecurityPolicy.PermitAll.class, p);
        }

        @Test
        @DisplayName("@RolesAllowed(\"admin\") → SecurityPolicy.Constrained with roles")
        void rolesOnly() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(NoAnnotations.class.getMethod("roles"), NoAnnotations.class);
            SecurityPolicy.Constrained c = assertInstanceOf(SecurityPolicy.Constrained.class, p);
            assertEquals(List.of("admin"), c.requiredRoles());
            assertEquals(List.of(), c.requiredScopes());
            assertFalse(c.requireAllScopes());
        }

        @Test
        @DisplayName("@Authorized with no scopes → SecurityPolicy.AuthenticatedOnly")
        void authNoScope() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(NoAnnotations.class.getMethod("authNoScope"), NoAnnotations.class);
            assertInstanceOf(SecurityPolicy.AuthenticatedOnly.class, p);
        }

        @Test
        @DisplayName("@Authorized with scopes → SecurityPolicy.Constrained with scopes (matchAll defaults to true)")
        void authScopes() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(NoAnnotations.class.getMethod("authScopes"), NoAnnotations.class);
            SecurityPolicy.Constrained c = assertInstanceOf(SecurityPolicy.Constrained.class, p);
            assertEquals(List.of("read"), c.requiredScopes());
            // @Authorized.matchAll defaults to true; the default surfaces as requireAllScopes=true.
            assertTrue(c.requireAllScopes());
        }

        @Test
        @DisplayName("@Authorized with scopes + matchAll → SecurityPolicy.Constrained with matchAll=true")
        void authScopesMatchAll() throws NoSuchMethodException {
            SecurityPolicy p =
                    resolver.resolve(NoAnnotations.class.getMethod("authScopesMatchAll"), NoAnnotations.class);
            SecurityPolicy.Constrained c = assertInstanceOf(SecurityPolicy.Constrained.class, p);
            assertTrue(c.requireAllScopes());
        }

        @Test
        @DisplayName("@RolesAllowed + @Authorized scopes → Constrained carrying both")
        void rolesPlusAuth() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(NoAnnotations.class.getMethod("rolesPlusAuth"), NoAnnotations.class);
            SecurityPolicy.Constrained c = assertInstanceOf(SecurityPolicy.Constrained.class, p);
            assertEquals(List.of("admin"), c.requiredRoles());
            assertEquals(List.of("read"), c.requiredScopes());
        }

        @Test
        @DisplayName("no annotations anywhere → SecurityPolicy.None")
        void none() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(NoAnnotations.class.getMethod("m"), NoAnnotations.class);
            assertInstanceOf(SecurityPolicy.None.class, p);
        }
    }

    // --- Class-vs-method override ---

    @Nested
    @DisplayName("resolve — Jakarta EE override: method-level wins over class-level")
    class JakartaOverride {

        @Test
        @DisplayName("class @PermitAll + method @DenyAll → DenyAll wins")
        void methodDenyOverridesClassPermit() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(ClassPermitAll.class.getMethod("mDeny"), ClassPermitAll.class);
            assertInstanceOf(SecurityPolicy.DenyAll.class, p);
        }

        @Test
        @DisplayName("class @PermitAll + method @RolesAllowed → Constrained wins (no class fallthrough)")
        void methodRolesOverridesClassPermit() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(ClassPermitAll.class.getMethod("mRoles"), ClassPermitAll.class);
            assertInstanceOf(SecurityPolicy.Constrained.class, p);
        }

        @Test
        @DisplayName("class @PermitAll + method @Authorized → AuthenticatedOnly wins")
        void methodAuthOverridesClassPermit() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(ClassPermitAll.class.getMethod("mAuth"), ClassPermitAll.class);
            assertInstanceOf(SecurityPolicy.AuthenticatedOnly.class, p);
        }

        @Test
        @DisplayName("class @PermitAll + method bare → falls through to class @PermitAll")
        void noMethodAnnotationFallsThroughToClass() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(ClassPermitAll.class.getMethod("m"), ClassPermitAll.class);
            assertInstanceOf(SecurityPolicy.PermitAll.class, p);
        }

        @Test
        @DisplayName("class @RolesAllowed + method bare → Constrained inherits class roles")
        void classRolesInheritedByMethod() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(ClassRoles.class.getMethod("m"), ClassRoles.class);
            SecurityPolicy.Constrained c = assertInstanceOf(SecurityPolicy.Constrained.class, p);
            assertEquals(List.of("user"), c.requiredRoles());
        }

        @Test
        @DisplayName("class @Authorized scopes + method bare → Constrained inherits class scopes")
        void classAuthInheritedByMethod() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(ClassAuthScopes.class.getMethod("m"), ClassAuthScopes.class);
            SecurityPolicy.Constrained c = assertInstanceOf(SecurityPolicy.Constrained.class, p);
            assertEquals(List.of("read", "write"), c.requiredScopes());
            assertTrue(c.requireAllScopes());
        }
    }

    // --- Conflict detection ---

    @Nested
    @DisplayName("hasConflictingAnnotations — detects conflicts at each level independently")
    class ConflictDetection {

        @Test
        @DisplayName("method @DenyAll + @PermitAll → conflict at method-level")
        void methodLevelConflict() throws NoSuchMethodException {
            assertTrue(resolver.hasConflictingAnnotations(
                    NoAnnotations.class, NoAnnotations.class.getMethod("conflictMethod")));
        }

        @Test
        @DisplayName("method @PermitAll + @RolesAllowed → conflict at method-level")
        void methodLevelConflict2() throws NoSuchMethodException {
            assertTrue(resolver.hasConflictingAnnotations(
                    NoAnnotations.class, NoAnnotations.class.getMethod("conflictMethod2")));
        }

        @Test
        @DisplayName("no conflicts → returns false")
        void noConflict() throws NoSuchMethodException {
            assertFalse(
                    resolver.hasConflictingAnnotations(NoAnnotations.class, NoAnnotations.class.getMethod("roles")));
        }

        @Test
        @DisplayName("describeConflict reports method-level conflict listing all annotations")
        void describeMethodConflict() throws NoSuchMethodException {
            String d = resolver.describeConflict(NoAnnotations.class, NoAnnotations.class.getMethod("conflictMethod"));
            assertTrue(d.contains("method-level"), "Description must identify method-level: " + d);
            assertTrue(d.contains("@DenyAll") && d.contains("@PermitAll"), "Description must list conflicts: " + d);
        }

        @Test
        @DisplayName("describeConflict on non-conflicting method falls into class branch (returns 'class-level: ')")
        void describeNoConflictReturnsClassBranch() throws NoSuchMethodException {
            String d = resolver.describeConflict(NoAnnotations.class, NoAnnotations.class.getMethod("roles"));
            assertTrue(d.startsWith("class-level"), "Must default to class branch when no method conflict: " + d);
        }
    }

    // --- Empty roles ---

    @Nested
    @DisplayName("hasEmptyRolesAllowed — detects empty @RolesAllowed at the effective level")
    class EmptyRoles {

        @Test
        @DisplayName("method @RolesAllowed({}) → true")
        void methodEmptyRoles() throws NoSuchMethodException {
            assertTrue(resolver.hasEmptyRolesAllowed(NoAnnotations.class, NoAnnotations.class.getMethod("emptyRoles")));
        }

        @Test
        @DisplayName("method @RolesAllowed(\"admin\") → false")
        void methodNonEmptyRoles() throws NoSuchMethodException {
            assertFalse(resolver.hasEmptyRolesAllowed(NoAnnotations.class, NoAnnotations.class.getMethod("roles")));
        }

        @Test
        @DisplayName("class @RolesAllowed({}) + method bare → true (inherited)")
        void classEmptyRolesInherited() throws NoSuchMethodException {
            assertTrue(resolver.hasEmptyRolesAllowed(ClassEmptyRoles.class, ClassEmptyRoles.class.getMethod("m")));
        }

        @Test
        @DisplayName("class @RolesAllowed({}) + method @PermitAll → false (method overrides)")
        void methodOverridesClassEmpty() throws NoSuchMethodException {
            // Build a method-only annotation list manually for this combination.
            assertFalse(resolver.hasEmptyRolesAllowedFrom(
                    List.of(annotationOf(NoAnnotations.class, "permit")),
                    List.of(annotationOf(ClassEmptyRoles.class, null))));
        }
    }

    // --- Interface walk ---

    @Nested
    @DisplayName("interface-declared annotations — honoured via AnnotationResolver")
    class InterfaceWalk {

        @Test
        @DisplayName("@RolesAllowed on interface method → impl's resolved policy is Constrained")
        void interfaceMethodRoles() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(ProtectedImpl.class.getMethod("op"), ProtectedImpl.class);
            SecurityPolicy.Constrained c = assertInstanceOf(SecurityPolicy.Constrained.class, p);
            assertEquals(List.of("admin"), c.requiredRoles());
        }

        @Test
        @DisplayName("@PermitAll on interface class → impl's resolved policy is PermitAll")
        void interfaceClassPermitAll() throws NoSuchMethodException {
            SecurityPolicy p = resolver.resolve(PublicImpl.class.getMethod("op"), PublicImpl.class);
            assertInstanceOf(SecurityPolicy.PermitAll.class, p);
        }
    }

    // --- FromAnnotations variants — exercise the public list-based overloads directly ---

    @Nested
    @DisplayName("FromAnnotations variants — accept pre-resolved annotation lists")
    class FromAnnotations {

        @Test
        @DisplayName("resolveFromAnnotations with method @DenyAll → DenyAll")
        void resolveFromAnnotations_denyAll() throws NoSuchMethodException {
            SecurityPolicy p =
                    resolver.resolveFromAnnotations(List.of(annotationOf(NoAnnotations.class, "deny")), List.of());
            assertInstanceOf(SecurityPolicy.DenyAll.class, p);
        }

        @Test
        @DisplayName("hasConflictingAnnotationsFrom with class-level conflict → true")
        void conflictingFrom_classLevel() throws NoSuchMethodException {
            // Build a class-level conflict via two annotations on the same level.
            assertTrue(resolver.hasConflictingAnnotationsFrom(
                    List.of(),
                    List.of(annotationOf(NoAnnotations.class, "deny"), annotationOf(NoAnnotations.class, "permit"))));
        }

        @Test
        @DisplayName("describeConflictFrom with class-level conflict → 'class-level: ...'")
        void describeFrom_classLevel() throws NoSuchMethodException {
            String d = resolver.describeConflictFrom(
                    List.of(),
                    List.of(annotationOf(NoAnnotations.class, "deny"), annotationOf(NoAnnotations.class, "permit")));
            assertTrue(d.startsWith("class-level"), "Class-level conflict must surface in description: " + d);
        }

        @Test
        @DisplayName("hasEmptyRolesAllowedFrom with class-level empty roles + method bare → true")
        void emptyRolesFrom_classLevel() {
            assertTrue(
                    resolver.hasEmptyRolesAllowedFrom(List.of(), List.of(annotationOf(ClassEmptyRoles.class, null))));
        }
    }

    // --- SecurityPolicyResolver default-method coverage ---

    @Nested
    @DisplayName("SecurityPolicyResolver interface defaults — delegate to AnnotationSecurityPolicyResolver")
    class InterfaceDefaults {

        /** Minimal impl that only implements the abstract methods, exercising default delegations. */
        static final class MinimalResolver implements SecurityPolicyResolver {
            @Override
            public SecurityPolicy resolve(Method method, Class<?> resourceClass) {
                return new SecurityPolicy.None();
            }

            @Override
            public boolean hasConflictingAnnotations(Class<?> resourceClass, Method method) {
                return false;
            }

            @Override
            public String describeConflict(Class<?> resourceClass, Method method) {
                return "";
            }

            @Override
            public boolean hasEmptyRolesAllowed(Class<?> resourceClass, Method method) {
                return false;
            }
        }

        private final SecurityPolicyResolver minimal = new MinimalResolver();

        @Test
        @DisplayName("default resolveFromAnnotations delegates → returns correct policy")
        void defaultResolveFrom() throws NoSuchMethodException {
            SecurityPolicy p =
                    minimal.resolveFromAnnotations(List.of(annotationOf(NoAnnotations.class, "permit")), List.of());
            assertInstanceOf(SecurityPolicy.PermitAll.class, p);
        }

        @Test
        @DisplayName("default hasConflictingAnnotationsFrom delegates → detects class-level conflict")
        void defaultConflictingFrom() throws NoSuchMethodException {
            assertTrue(minimal.hasConflictingAnnotationsFrom(
                    List.of(),
                    List.of(annotationOf(NoAnnotations.class, "deny"), annotationOf(NoAnnotations.class, "permit"))));
        }

        @Test
        @DisplayName("default describeConflictFrom delegates → identifies class-level conflict")
        void defaultDescribeFrom() throws NoSuchMethodException {
            String d = minimal.describeConflictFrom(
                    List.of(),
                    List.of(annotationOf(NoAnnotations.class, "deny"), annotationOf(NoAnnotations.class, "permit")));
            assertTrue(d.startsWith("class-level"));
        }

        @Test
        @DisplayName("default hasEmptyRolesAllowedFrom delegates → detects empty roles")
        void defaultEmptyRolesFrom() {
            assertTrue(minimal.hasEmptyRolesAllowedFrom(
                    List.of(annotationOf(NoAnnotations.class, "emptyRoles")), List.of()));
        }
    }

    // --- Helpers ---

    /**
     * Reads a single annotation from either a class (when {@code memberName} is {@code null}) or a
     * specific method by name. Used to build per-test annotation lists for the FromAnnotations
     * variants without spinning up extra fixture classes.
     */
    private static Annotation annotationOf(Class<?> source, String memberName) {
        if (memberName == null) {
            Annotation[] anns = source.getAnnotations();
            if (anns.length == 0) {
                throw new IllegalStateException("No class-level annotations on " + source.getName());
            }
            return anns[0];
        }
        try {
            Method m = source.getMethod(memberName);
            Annotation[] anns = m.getAnnotations();
            if (anns.length == 0) {
                throw new IllegalStateException("No annotations on " + source.getName() + "#" + memberName);
            }
            return anns[0];
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
