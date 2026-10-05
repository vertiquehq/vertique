// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.authz.ancestor.AncestorDenyPolicy;
import dev.vertique.security.authz.ancestor.PackageAncestor;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Truth table for typed-policy resolution. The assertions name requirements and rejections, not the
 * existence of the new types.
 */
class AccessPolicyResolverTest {

    @Test
    @DisplayName("resolve, select, and collect the complete consumer hierarchy")
    void shouldResolveOrRejectTheCompleteConsumerHierarchy() throws Exception {
        assertDirectRequirements();
        assertMalformedPoliciesRejected();
        assertExclusiveRequirementsRejected();
        assertValueValidationRejected();
        assertComposition();
        assertHarmlessMetadata();
        assertSelectionTable();
        assertCollection();
    }

    private static void assertDirectRequirements() {
        assertSingle(PermitPolicy.class, PermitAll.class);
        assertSingle(DenyPolicy.class, DenyAll.class);
        List<Annotation> authenticated = AccessPolicyResolver.resolve(AuthenticatedPolicy.class);
        assertEquals(1, authenticated.size());
        Authorized authorized = (Authorized) authenticated.get(0);
        assertEquals(0, authorized.scopes().length);

        List<Annotation> roles = AccessPolicyResolver.resolve(RolesPolicy.class);
        assertEquals(List.of("admin"), List.of(((RolesAllowed) roles.get(0)).value()));

        List<Annotation> scopes = AccessPolicyResolver.resolve(ScopePolicy.class);
        Authorized scoped = (Authorized) scopes.get(0);
        assertEquals(List.of("write"), List.of(scoped.scopes()));
        assertTrue(scoped.matchAll());

        List<Annotation> action = AccessPolicyResolver.resolve(ActionPolicy.class);
        assertEquals("cms.content.read", ((RequiresAction) action.get(0)).value());

        List<Annotation> combo = AccessPolicyResolver.resolve(ComboPolicy.class);
        assertEquals(3, combo.size());
        assertTrue(combo.stream()
                .anyMatch(ann -> ann instanceof RolesAllowed rolesAllowed
                        && List.of(rolesAllowed.value()).equals(List.of("admin"))));
        assertTrue(combo.stream()
                .anyMatch(ann -> ann instanceof Authorized auth
                        && List.of(auth.scopes()).equals(List.of("write"))
                        && auth.matchAll()));
        assertTrue(combo.stream()
                .anyMatch(ann -> ann instanceof RequiresAction required
                        && required.value().equals("cms.content.read")));
    }

    private static void assertMalformedPoliciesRejected() {
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(AccessPolicy.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(GenericPolicy.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(ExtraPolicy.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(MethodPolicy.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(FieldPolicy.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(NestedPolicy.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(PackagePolicy.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(MetaPolicy.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(EmptyPolicy.class));
    }

    private static void assertExclusiveRequirementsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(PermitAndRoles.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(PermitAndAuthorized.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(PermitAndDeny.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(PermitAndAction.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(DenyAndRoles.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(DenyAndAuthorized.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(DenyAndAction.class));
    }

    private static void assertValueValidationRejected() {
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(EmptyRolesPolicy.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(BlankRolePolicy.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(BlankScopePolicy.class));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(BadActionPolicy.class));
    }

    private static void assertComposition() {
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(RuntimeComposedPolicy.class));
        assertSingle(PermitPlusClassComposition.class, PermitAll.class);
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(ClassCompositionOnly.class));
        assertSingle(PermitPlusSourceComposition.class, PermitAll.class);
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(SourceCompositionOnly.class));
        assertSingle(PermitPlusDefaultComposition.class, PermitAll.class);
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(DefaultCompositionOnly.class));
        assertSingle(PermitPlusHiddenIntermediate.class, PermitAll.class);
        assertThrows(IllegalArgumentException.class, () -> AccessPolicyResolver.resolve(HiddenIntermediateOnly.class));
        List<Annotation> direct = AccessPolicyResolver.resolve(DirectRolePlusHidden.class);
        assertEquals(1, direct.size());
        assertEquals(List.of("admin"), List.of(((RolesAllowed) direct.get(0)).value()));
    }

    private static void assertHarmlessMetadata() {
        assertSingle(NotedPermit.class, PermitAll.class);
    }

    private static void assertSelectionTable() {
        RequiresPolicy permit = RepeatA.class.getAnnotation(RequiresPolicy.class);
        RequiresPolicy permitAgain = RepeatB.class.getAnnotation(RequiresPolicy.class);
        RequiresPolicy deny = DenyHolder.class.getAnnotation(RequiresPolicy.class);
        RequiresPolicy roles = RolesHolder.class.getAnnotation(RequiresPolicy.class);

        assertEquals(Optional.of(DenyPolicy.class), AccessPolicyResolver.select(List.of(deny), List.of(permit)));
        assertEquals(
                Optional.of(PermitPolicy.class), AccessPolicyResolver.select(List.of(), List.of(permit, permitAgain)));
        assertThrows(
                IllegalArgumentException.class, () -> AccessPolicyResolver.select(List.of(), List.of(permit, deny)));
        assertThrows(
                IllegalArgumentException.class,
                () -> AccessPolicyResolver.select(List.of(roles), List.of(permit, deny)));
        assertThrows(
                IllegalArgumentException.class, () -> AccessPolicyResolver.select(List.of(permit, deny), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> AccessPolicyResolver.select(List.of(permit, deny), List.of(roles)));
        assertThrows(
                IllegalArgumentException.class,
                () -> AccessPolicyResolver.select(
                        List.of(permit), List.of(BadHolder.class.getAnnotation(RequiresPolicy.class))));
        assertThrows(
                IllegalArgumentException.class,
                () -> AccessPolicyResolver.select(List.of(), List.of(Mixed.class.getAnnotations())));
        assertThrows(
                IllegalArgumentException.class,
                () -> AccessPolicyResolver.select(List.of(Mixed.class.getAnnotations()), List.of()));
        assertEquals(
                Optional.empty(),
                AccessPolicyResolver.select(List.of(), List.of(Plain.class.getAnnotation(MarkerA.class))));
    }

    private static void assertCollection() throws Exception {
        Method inherited = Base.class.getDeclaredMethod("inherited");
        List<Annotation> legacy =
                List.of(inherited.getAnnotation(MarkerA.class), inherited.getAnnotation(MarkerB.class));
        assertSame(legacy, AccessPolicyResolver.collectMethodAnnotations(Base.class, inherited, legacy));

        List<Annotation> fromConsumer =
                AccessPolicyResolver.collectMethodAnnotations(Consumer.class, inherited, legacy);
        assertEquals(legacy.get(0), fromConsumer.get(0));
        assertEquals(legacy.get(1), fromConsumer.get(1));
        assertTrue(containsPolicy(fromConsumer, AdminPolicy.class));
        assertFalse(containsPolicy(fromConsumer, DenyPolicy.class));

        Method work = Overloads.class.getDeclaredMethod("work");
        List<Annotation> overload = AccessPolicyResolver.collectMethodAnnotations(Overloads.class, work, List.of());
        assertTrue(containsPolicy(overload, DenyPolicy.class));
        assertFalse(containsPolicy(overload, PermitPolicy.class));
        assertFalse(containsPolicy(overload, AdminPolicy.class));
        assertFalse(containsPolicy(overload, ActionPolicy.class));

        Method hostOp = Host.class.getDeclaredMethod("op");
        List<Annotation> replaced = AccessPolicyResolver.collectMethodAnnotations(Host.class, hostOp, legacy);
        assertEquals(legacy.get(0), replaced.get(0));
        assertEquals(legacy.get(1), replaced.get(1));
        assertTrue(containsPolicy(replaced, DenyPolicy.class));
        assertFalse(containsPolicy(replaced, PermitPolicy.class));

        assertThrows(
                IllegalArgumentException.class,
                () -> AccessPolicyResolver.collectMethodAnnotations(
                        ChildType.class, ChildType.class.getDeclaredMethod("op"), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> AccessPolicyResolver.collectMethodAnnotations(
                        BadHost.class, BadHost.class.getDeclaredMethod("op"), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> AccessPolicyResolver.collectMethodAnnotations(
                        Diamond.class, Diamond.class.getDeclaredMethod("op"), List.of()));

        Method take = Leaf.class.getDeclaredMethod("take", String.class);
        assertTrue(containsPolicy(
                AccessPolicyResolver.collectMethodAnnotations(Leaf.class, take, List.of()), AdminPolicy.class));

        Method arrayTake = ArrayHost.class.getDeclaredMethod("take", String[].class);
        assertTrue(containsPolicy(
                AccessPolicyResolver.collectMethodAnnotations(ArrayHost.class, arrayTake, List.of()),
                AdminPolicy.class));
        Method objectTake = ArrayHost.class.getDeclaredMethod("take", Object.class);
        assertFalse(containsPolicy(
                AccessPolicyResolver.collectMethodAnnotations(ArrayHost.class, objectTake, List.of()),
                AdminPolicy.class));
        Method listArray = ArraySelf.class.getDeclaredMethod("take", List[].class);
        assertTrue(containsPolicy(
                AccessPolicyResolver.collectMethodAnnotations(ArraySelf.class, listArray, List.of()),
                AdminPolicy.class));

        Method realGet = StringHolder.class.getDeclaredMethod("get");
        Method bridge = Arrays.stream(StringHolder.class.getDeclaredMethods())
                .filter(method -> method.isBridge() && method.getName().equals("get"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                1, policyCount(AccessPolicyResolver.collectMethodAnnotations(StringHolder.class, realGet, List.of())));
        assertEquals(
                1, policyCount(AccessPolicyResolver.collectMethodAnnotations(StringHolder.class, bridge, List.of())));
        assertTrue(containsPolicy(
                AccessPolicyResolver.collectMethodAnnotations(StringHolder.class, bridge, List.of()),
                PermitPolicy.class));

        Method inheritedBridge = InheritedBridgeResource.class.getMethod("get");
        assertTrue(inheritedBridge.isBridge());
        assertTrue(containsPolicy(
                AccessPolicyResolver.collectMethodAnnotations(
                        InheritedBridgeResource.class, inheritedBridge, List.of()),
                AdminPolicy.class));

        Method hidden = ChildOfAncestor.class.getDeclaredMethod("hidden");
        assertFalse(containsPolicy(
                AccessPolicyResolver.collectMethodAnnotations(ChildOfAncestor.class, hidden, List.of()),
                AncestorDenyPolicy.class));
    }

    @Test
    @DisplayName("the scanned method always corresponds to itself, so its own security is never replaced")
    void shouldNeverDropTheScannedMethodsOwnSecurityDeclarations() throws Exception {
        Method create = GenericAncestor.class.getDeclaredMethod("create", Object.class);
        List<Annotation> collected = AccessPolicyResolver.collectMethodAnnotations(
                GenericConsumer.class, create, Arrays.asList(create.getAnnotations()));
        assertTrue(containsPolicy(collected, AdminPolicy.class), "the inherited method's own policy must be kept");
        assertFalse(containsPolicy(collected, PermitPolicy.class), "the type policy must not replace it");

        Method inlineOp = GenericAncestor.class.getDeclaredMethod("inlineOp", Object.class);
        assertThrows(
                IllegalArgumentException.class,
                () -> AccessPolicyResolver.collectMethodAnnotations(
                        GenericConsumer.class, inlineOp, Arrays.asList(inlineOp.getAnnotations())),
                "inline security on the scanned method mixed with a type policy must reject, not be stripped");

        Method secret = NonPublicHost.class.getDeclaredMethod("secret");
        assertThrows(
                IllegalArgumentException.class,
                () -> AccessPolicyResolver.collectMethodAnnotations(
                        NonPublicHost.class, secret, Arrays.asList(secret.getAnnotations())),
                "a non-public scanned method carrying inline security under a type policy must reject");

        Method own = NonPublicHost.class.getDeclaredMethod("own");
        List<Annotation> ownCollected = AccessPolicyResolver.collectMethodAnnotations(
                NonPublicHost.class, own, Arrays.asList(own.getAnnotations()));
        assertTrue(containsPolicy(ownCollected, DenyPolicy.class), "a non-public scanned method keeps its own policy");
        assertFalse(containsPolicy(ownCollected, PermitPolicy.class));
    }

    @Test
    @DisplayName("security annotations the legacy list holds but collection cannot reproduce fail closed")
    void shouldRejectLegacySecurityTheConsumerRootedSetsDidNotReproduce() throws Exception {
        Method create = GenericAncestor.class.getDeclaredMethod("create", Object.class);
        List<Annotation> foreign =
                List.of(UnreachableDecl.class.getDeclaredMethod("elsewhere").getAnnotation(RolesAllowed.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> AccessPolicyResolver.collectMethodAnnotations(GenericConsumer.class, create, foreign));
    }

    private static void assertSingle(Class<? extends AccessPolicy> policy, Class<? extends Annotation> type) {
        List<Annotation> resolved = AccessPolicyResolver.resolve(policy);
        assertEquals(1, resolved.size(), policy.getSimpleName());
        assertEquals(type, resolved.get(0).annotationType());
    }

    private static boolean containsPolicy(List<Annotation> annotations, Class<? extends AccessPolicy> policy) {
        return annotations.stream()
                .anyMatch(ann ->
                        ann.annotationType() == RequiresPolicy.class && ((RequiresPolicy) ann).value() == policy);
    }

    private static long policyCount(List<Annotation> annotations) {
        return annotations.stream()
                .filter(ann -> ann.annotationType() == RequiresPolicy.class)
                .count();
    }

    @PermitAll
    public interface PermitPolicy extends AccessPolicy {}

    @DenyAll
    public interface DenyPolicy extends AccessPolicy {}

    @Authorized
    public interface AuthenticatedPolicy extends AccessPolicy {}

    @RolesAllowed("admin")
    public interface RolesPolicy extends AccessPolicy {}

    @Authorized(scopes = "write")
    public interface ScopePolicy extends AccessPolicy {}

    @RequiresAction("cms.content.read")
    public interface ActionPolicy extends AccessPolicy {}

    @RolesAllowed("admin")
    @Authorized(scopes = "write")
    @RequiresAction("cms.content.read")
    public interface ComboPolicy extends AccessPolicy {}

    @RolesAllowed("admin")
    public interface AdminPolicy extends AccessPolicy {}

    public interface EmptyPolicy extends AccessPolicy {}

    public interface GenericPolicy<T> extends AccessPolicy {}

    public interface ExtraPolicy extends AccessPolicy, Marker {}

    public interface MethodPolicy extends AccessPolicy {
        void run();
    }

    public interface FieldPolicy extends AccessPolicy {
        int N = 1;
    }

    public interface NestedPolicy extends AccessPolicy {
        interface Inner {}
    }

    @RequiresPolicy(PermitPolicy.class)
    public interface MetaPolicy extends AccessPolicy {}

    @PermitAll
    @RolesAllowed("admin")
    public interface PermitAndRoles extends AccessPolicy {}

    @PermitAll
    @Authorized
    public interface PermitAndAuthorized extends AccessPolicy {}

    @PermitAll
    @DenyAll
    public interface PermitAndDeny extends AccessPolicy {}

    @PermitAll
    @RequiresAction("cms.content.read")
    public interface PermitAndAction extends AccessPolicy {}

    @DenyAll
    @RolesAllowed("admin")
    public interface DenyAndRoles extends AccessPolicy {}

    @DenyAll
    @Authorized
    public interface DenyAndAuthorized extends AccessPolicy {}

    @DenyAll
    @RequiresAction("cms.content.read")
    public interface DenyAndAction extends AccessPolicy {}

    @RolesAllowed({})
    public interface EmptyRolesPolicy extends AccessPolicy {}

    @RolesAllowed(" ")
    public interface BlankRolePolicy extends AccessPolicy {}

    @Authorized(scopes = " ")
    public interface BlankScopePolicy extends AccessPolicy {}

    @RequiresAction("not-an-action")
    public interface BadActionPolicy extends AccessPolicy {}

    @RuntimeComposed
    public interface RuntimeComposedPolicy extends AccessPolicy {}

    @PermitAll
    @ClassComposed
    public interface PermitPlusClassComposition extends AccessPolicy {}

    @ClassComposed
    public interface ClassCompositionOnly extends AccessPolicy {}

    @PermitAll
    @SourceComposed
    public interface PermitPlusSourceComposition extends AccessPolicy {}

    @SourceComposed
    public interface SourceCompositionOnly extends AccessPolicy {}

    @PermitAll
    @DefaultComposed
    public interface PermitPlusDefaultComposition extends AccessPolicy {}

    @DefaultComposed
    public interface DefaultCompositionOnly extends AccessPolicy {}

    @PermitAll
    @VisibleOuter
    public interface PermitPlusHiddenIntermediate extends AccessPolicy {}

    @VisibleOuter
    public interface HiddenIntermediateOnly extends AccessPolicy {}

    @RolesAllowed("admin")
    @ClassComposed
    public interface DirectRolePlusHidden extends AccessPolicy {}

    @PermitAll
    @Note("ok")
    public interface NotedPermit extends AccessPolicy {}

    @RequiresPolicy(PermitPolicy.class)
    private static final class RepeatA {}

    @RequiresPolicy(PermitPolicy.class)
    private static final class RepeatB {}

    @RequiresPolicy(DenyPolicy.class)
    private static final class DenyHolder {}

    @RequiresPolicy(RolesPolicy.class)
    private static final class RolesHolder {}

    @RequiresPolicy(EmptyPolicy.class)
    private static final class BadHolder {}

    @RequiresPolicy(PermitPolicy.class)
    @PermitAll
    private static final class Mixed {}

    @MarkerA
    private static final class Plain {
        @MarkerA
        @MarkerB
        public void op() {}
    }

    public static class Base {
        @MarkerA
        @MarkerB
        public String inherited() {
            return "ok";
        }
    }

    public static class Consumer extends Base implements SecuredOps {}

    public interface SecuredOps {
        @RequiresPolicy(AdminPolicy.class)
        String inherited();
    }

    public static class Overloads {
        @RequiresPolicy(DenyPolicy.class)
        public void work() {}

        @RequiresPolicy(PermitPolicy.class)
        public void work(int ignored) {}

        @RequiresPolicy(AdminPolicy.class)
        private void hidden() {}

        @RequiresPolicy(ActionPolicy.class)
        public static void stat() {}
    }

    @RequiresPolicy(PermitPolicy.class)
    public static class Host {
        @RequiresPolicy(DenyPolicy.class)
        @MarkerA
        @MarkerB
        public void op() {}
    }

    @RequiresPolicy(PermitPolicy.class)
    public static class ParentType {
        public void op() {}
    }

    @RequiresPolicy(DenyPolicy.class)
    public static class ChildType extends ParentType {
        @Override
        public void op() {}
    }

    @RequiresPolicy(EmptyPolicy.class)
    public static class BadHost {
        @RequiresPolicy(PermitPolicy.class)
        public void op() {}
    }

    public interface Left {
        @RequiresPolicy(PermitPolicy.class)
        void op();
    }

    public interface Right {
        @RequiresPolicy(DenyPolicy.class)
        void op();
    }

    public static class Diamond implements Left, Right {
        @Override
        public void op() {}
    }

    public interface Mid<T> {
        @RequiresPolicy(AdminPolicy.class)
        void take(T value);
    }

    public interface Next extends Mid<String> {}

    public static class Leaf implements Next {
        @Override
        public void take(String value) {}
    }

    public interface ArrayApi<T> {
        @RequiresPolicy(AdminPolicy.class)
        String take(T[] rows);
    }

    public static class ArrayHost implements ArrayApi<String> {
        @Override
        public String take(String[] rows) {
            return "";
        }

        public String take(Object value) {
            return "";
        }
    }

    public static class ArraySelf {
        @RequiresPolicy(AdminPolicy.class)
        public String take(List<String>[] rows) {
            return "";
        }
    }

    public interface Holder<T> {
        @RequiresPolicy(PermitPolicy.class)
        T get();
    }

    public static class StringHolder implements Holder<String> {
        @Override
        public String get() {
            return "s";
        }
    }

    public static class ChildOfAncestor extends PackageAncestor {
        public void hidden() {}
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @RolesAllowed("admin")
    public @interface RuntimeComposed {}

    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
    @RolesAllowed("admin")
    public @interface ClassComposed {}

    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    @RolesAllowed("admin")
    public @interface SourceComposed {}

    @Target(ElementType.TYPE)
    @RolesAllowed("admin")
    public @interface DefaultComposed {}

    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
    @RolesAllowed("admin")
    public @interface HiddenIntermediate {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @HiddenIntermediate
    public @interface VisibleOuter {}

    public static class GenericAncestor<T> {
        @RequiresPolicy(AdminPolicy.class)
        public void create(T body) {}

        @RolesAllowed("admin")
        public void inlineOp(T body) {}
    }

    @RequiresPolicy(PermitPolicy.class)
    public static class GenericConsumer extends GenericAncestor<String> {}

    @RequiresPolicy(PermitPolicy.class)
    public static class NonPublicHost {
        @RolesAllowed("admin")
        private void secret() {}

        @RequiresPolicy(DenyPolicy.class)
        private void own() {}
    }

    public static class UnreachableDecl {
        @RolesAllowed("elsewhere")
        public void elsewhere() {}
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface MarkerA {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface MarkerB {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Note {
        String value();
    }

    public interface Marker {}
}

/** Package-private policies are not valid policies. */
interface PackagePolicy extends AccessPolicy {}
