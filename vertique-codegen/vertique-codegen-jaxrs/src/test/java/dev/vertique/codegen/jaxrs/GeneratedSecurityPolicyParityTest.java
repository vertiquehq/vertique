// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.rest.core.security.RequiresActionResolver;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.JaxRsRouteRegistrar;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.Authorized;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.authz.RequiresPolicy;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins that the generated descriptor builds the same {@link SecurityPolicy} value the reflective
 * {@code AnnotationSecurityPolicyResolver} builds, including {@code requireAllScopes}.
 *
 * <p>The runtime sets {@code requireAllScopes} only when {@code @Authorized} declares scopes;
 * with no scopes the flag is {@code false}. The enforcer ignores the flag when there are no
 * scopes, so the value is inert there, but the generated {@code ResourceMethodMeta} must still
 * equal the scanner's.
 */
class GeneratedSecurityPolicyParityTest {

    private static final String PKG = "dev.vertique.test.secparity";

    @Path("/roles")
    static class RolesOnly {
        @GET
        @RolesAllowed("admin")
        public String get() {
            return "";
        }
    }

    @Path("/roles-authorized")
    static class RolesAndScopelessAuthorized {
        @GET
        @RolesAllowed("admin")
        @Authorized
        public String get() {
            return "";
        }
    }

    @Path("/scopes")
    static class ScopesDefaultMatch {
        @GET
        @Authorized(scopes = {"read", "write"})
        public String get() {
            return "";
        }
    }

    /** Admin role lives on the interface the consumer adds. The implementation is on the base. */
    @RolesAllowed("admin")
    public interface InheritedAdminPolicy extends AccessPolicy {}

    interface InheritedPolicyOps {
        @GET
        @RequiresPolicy(InheritedAdminPolicy.class)
        String get();
    }

    static class InheritedPolicyBase {
        @GET
        public String get() {
            return "";
        }
    }

    @Path("/inherited-policy")
    static class InheritedInterfacePolicy extends InheritedPolicyBase implements InheritedPolicyOps {}

    /** Action-only type policy. The method policy must replace it in the generated contract. */
    @RequiresAction("hello.greeting.read")
    public interface ActionOnlyPolicy extends AccessPolicy {}

    @RolesAllowed("admin")
    public interface MethodAdminPolicy extends AccessPolicy {}

    @Path("/method-replaces-action")
    @RequiresPolicy(ActionOnlyPolicy.class)
    static class MethodReplacesActionPolicy {
        @GET
        @RequiresPolicy(MethodAdminPolicy.class)
        public String get() {
            return "";
        }
    }

    /** Method action policy replaces a type role policy. The role/scope contract is empty. */
    @RolesAllowed("admin")
    public interface TypeAdminPolicy extends AccessPolicy {}

    @RequiresAction("hello.greeting.read")
    public interface MethodActionPolicy extends AccessPolicy {}

    @Path("/action-replaces-roles")
    @RequiresPolicy(TypeAdminPolicy.class)
    static class ActionReplacesRolesPolicy {
        @GET
        @RequiresPolicy(MethodActionPolicy.class)
        public String get() {
            return "";
        }
    }

    /** A hidden private method is not the operation, so its policy cannot open the route. */
    @PermitAll
    public interface HiddenOpenPolicy extends AccessPolicy {}

    static class HiddenOpenBase {
        @RequiresPolicy(HiddenOpenPolicy.class)
        private String get() {
            return "";
        }
    }

    @Path("/hidden-open")
    @RequiresPolicy(TypeAdminPolicy.class)
    static class HiddenMethodDoesNotReplace extends HiddenOpenBase {
        @GET
        public String get() {
            return "";
        }
    }

    /** A static interface method is not inherited and cannot replace the type policy. */
    interface HiddenStaticOps {
        @RequiresPolicy(HiddenOpenPolicy.class)
        static String get() {
            return "";
        }
    }

    @Path("/hidden-static")
    @RequiresPolicy(TypeAdminPolicy.class)
    static class HiddenStaticDoesNotReplace implements HiddenStaticOps {
        @GET
        public String get() {
            return "";
        }
    }

    /** A package-private method in another package is hidden and cannot open the route. */
    @Path("/hidden-foreign")
    @RequiresPolicy(TypeAdminPolicy.class)
    static class ForeignPackageDoesNotReplace extends dev.vertique.codegen.jaxrs.hidden.ForeignHiddenBase {
        @GET
        public String get() {
            return "";
        }
    }

    /**
     * The admin policy lives only on {@code Mid<T>.take(T)}. The JAX-RS method is {@code take(String)}.
     * Erased matching sees {@code Object} and drops the policy.
     */
    public interface MidTake<T> {
        @RequiresPolicy(TypeAdminPolicy.class)
        String take(T value);
    }

    public interface NextTake extends MidTake<String> {}

    @Path("/generic-method-policy")
    static class GenericMethodPolicy implements NextTake {
        @GET
        @Override
        public String take(@jakarta.ws.rs.QueryParam("value") String value) {
            return "";
        }
    }

    /** A generic ancestor's own method policy, never overridden, must replace the consumer's type policy. */
    static class GenericAncestorBase<T> {
        @GET
        @RequiresPolicy(MethodAdminPolicy.class)
        public String get(@jakarta.ws.rs.QueryParam("value") T value) {
            return "";
        }
    }

    @Path("/generic-ancestor-method-policy")
    @RequiresPolicy(ActionOnlyPolicy.class)
    static class GenericAncestorMethodPolicy extends GenericAncestorBase<String> {}

    /** A same-package package-private method is part of the operation. Its action must be installed. */
    static class SamePackageActionBase {
        @RequiresPolicy(MethodActionPolicy.class)
        String get() {
            return "";
        }
    }

    @Path("/same-package-action")
    @RequiresPolicy(TypeAdminPolicy.class)
    static class SamePackageActionReplaces extends SamePackageActionBase {
        @GET
        @Override
        public String get() {
            return "";
        }
    }

    @Path("/scopes-any")
    static class ScopesMatchAny {
        @GET
        @Authorized(
                scopes = {"read", "write"},
                matchAll = false)
        public String get() {
            return "";
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(new RolesOnly(), "RolesOnly", """
                        @Path("/roles")
                        public class RolesOnly {
                            @GET
                            @RolesAllowed("admin")
                            public String get() { return ""; }
                        }
                        """),
                Arguments.of(new RolesAndScopelessAuthorized(), "RolesAndScopelessAuthorized", """
                        @Path("/roles-authorized")
                        public class RolesAndScopelessAuthorized {
                            @GET
                            @RolesAllowed("admin")
                            @Authorized
                            public String get() { return ""; }
                        }
                        """),
                Arguments.of(new ScopesDefaultMatch(), "ScopesDefaultMatch", """
                        @Path("/scopes")
                        public class ScopesDefaultMatch {
                            @GET
                            @Authorized(scopes = {"read", "write"})
                            public String get() { return ""; }
                        }
                        """),
                Arguments.of(new ScopesMatchAny(), "ScopesMatchAny", """
                        @Path("/scopes-any")
                        public class ScopesMatchAny {
                            @GET
                            @Authorized(scopes = {"read", "write"}, matchAll = false)
                            public String get() { return ""; }
                        }
                        """),
                Arguments.of(new InheritedInterfacePolicy(), "InheritedInterfacePolicy", """
                        import dev.vertique.security.authz.AccessPolicy;
                        import dev.vertique.security.authz.RequiresPolicy;

                        class PolicyTypes {
                            @jakarta.annotation.security.RolesAllowed("admin")
                            public interface InheritedAdminPolicy extends AccessPolicy {}
                        }

                        interface InheritedPolicyOps {
                            @GET
                            @RequiresPolicy(PolicyTypes.InheritedAdminPolicy.class)
                            String get();
                        }

                        class InheritedPolicyBase {
                            @GET
                            public String get() { return ""; }
                        }

                        @Path("/inherited-policy")
                        public class InheritedInterfacePolicy extends InheritedPolicyBase implements InheritedPolicyOps {}
                        """),
                Arguments.of(new MethodReplacesActionPolicy(), "MethodReplacesActionPolicy", """
                        import dev.vertique.security.authz.AccessPolicy;
                        import dev.vertique.security.authz.RequiresAction;
                        import dev.vertique.security.authz.RequiresPolicy;

                        class PolicyTypes {
                            @RequiresAction("hello.greeting.read")
                            public interface ActionOnlyPolicy extends AccessPolicy {}

                            @jakarta.annotation.security.RolesAllowed("admin")
                            public interface MethodAdminPolicy extends AccessPolicy {}
                        }

                        @RequiresPolicy(PolicyTypes.ActionOnlyPolicy.class)
                        @Path("/method-replaces-action")
                        public class MethodReplacesActionPolicy {
                            @GET
                            @RequiresPolicy(PolicyTypes.MethodAdminPolicy.class)
                            public String get() { return ""; }
                        }
                        """),
                Arguments.of(new ActionReplacesRolesPolicy(), "ActionReplacesRolesPolicy", """
                        import dev.vertique.security.authz.AccessPolicy;
                        import dev.vertique.security.authz.RequiresAction;
                        import dev.vertique.security.authz.RequiresPolicy;

                        class PolicyTypes {
                            @jakarta.annotation.security.RolesAllowed("admin")
                            public interface TypeAdminPolicy extends AccessPolicy {}

                            @RequiresAction("hello.greeting.read")
                            public interface MethodActionPolicy extends AccessPolicy {}
                        }

                        @RequiresPolicy(PolicyTypes.TypeAdminPolicy.class)
                        @Path("/action-replaces-roles")
                        public class ActionReplacesRolesPolicy {
                            @GET
                            @RequiresPolicy(PolicyTypes.MethodActionPolicy.class)
                            public String get() { return ""; }
                        }
                        """),
                Arguments.of(new HiddenMethodDoesNotReplace(), "HiddenMethodDoesNotReplace", """
                        import dev.vertique.security.authz.AccessPolicy;
                        import dev.vertique.security.authz.RequiresPolicy;

                        class PolicyTypes {
                            @jakarta.annotation.security.RolesAllowed("admin")
                            public interface TypeAdminPolicy extends AccessPolicy {}

                            @jakarta.annotation.security.PermitAll
                            public interface HiddenOpenPolicy extends AccessPolicy {}
                        }

                        class HiddenOpenBase {
                            @RequiresPolicy(PolicyTypes.HiddenOpenPolicy.class)
                            private String get() { return ""; }
                        }

                        @RequiresPolicy(PolicyTypes.TypeAdminPolicy.class)
                        @Path("/hidden-open")
                        public class HiddenMethodDoesNotReplace extends HiddenOpenBase {
                            @GET
                            public String get() { return ""; }
                        }
                        """),
                Arguments.of(new HiddenStaticDoesNotReplace(), "HiddenStaticDoesNotReplace", """
                        import dev.vertique.security.authz.AccessPolicy;
                        import dev.vertique.security.authz.RequiresPolicy;

                        class PolicyTypes {
                            @jakarta.annotation.security.RolesAllowed("admin")
                            public interface TypeAdminPolicy extends AccessPolicy {}

                            @jakarta.annotation.security.PermitAll
                            public interface HiddenOpenPolicy extends AccessPolicy {}
                        }

                        interface HiddenStaticOps {
                            @RequiresPolicy(PolicyTypes.HiddenOpenPolicy.class)
                            static String get() { return ""; }
                        }

                        @RequiresPolicy(PolicyTypes.TypeAdminPolicy.class)
                        @Path("/hidden-static")
                        public class HiddenStaticDoesNotReplace implements HiddenStaticOps {
                            @GET
                            public String get() { return ""; }
                        }
                        """),
                Arguments.of(new ForeignPackageDoesNotReplace(), "ForeignPackageDoesNotReplace", """
                        import dev.vertique.security.authz.AccessPolicy;
                        import dev.vertique.security.authz.RequiresPolicy;

                        class PolicyTypes {
                            @jakarta.annotation.security.RolesAllowed("admin")
                            public interface TypeAdminPolicy extends AccessPolicy {}
                        }

                        @RequiresPolicy(PolicyTypes.TypeAdminPolicy.class)
                        @Path("/hidden-foreign")
                        public class ForeignPackageDoesNotReplace
                                extends dev.vertique.test.hidden.ForeignHiddenBase {
                            @GET
                            public String get() { return ""; }
                        }
                        """),
                Arguments.of(new GenericMethodPolicy(), "GenericMethodPolicy", """
                        import dev.vertique.security.authz.AccessPolicy;
                        import dev.vertique.security.authz.RequiresPolicy;

                        class PolicyTypes {
                            @jakarta.annotation.security.RolesAllowed("admin")
                            public interface TypeAdminPolicy extends AccessPolicy {}
                        }

                        interface MidTake<T> {
                            @RequiresPolicy(PolicyTypes.TypeAdminPolicy.class)
                            String take(T value);
                        }

                        interface NextTake extends MidTake<String> {}

                        @Path("/generic-method-policy")
                        public class GenericMethodPolicy implements NextTake {
                            @GET
                            @Override
                            public String take(@jakarta.ws.rs.QueryParam("value") String value) { return ""; }
                        }
                        """),
                Arguments.of(new GenericAncestorMethodPolicy(), "GenericAncestorMethodPolicy", """
                        import dev.vertique.security.authz.AccessPolicy;
                        import dev.vertique.security.authz.RequiresAction;
                        import dev.vertique.security.authz.RequiresPolicy;

                        class PolicyTypes {
                            @RequiresAction("hello.greeting.read")
                            public interface ActionOnlyPolicy extends AccessPolicy {}

                            @jakarta.annotation.security.RolesAllowed("admin")
                            public interface MethodAdminPolicy extends AccessPolicy {}
                        }

                        class GenericAncestorBase<T> {
                            @GET
                            @RequiresPolicy(PolicyTypes.MethodAdminPolicy.class)
                            public String get(@jakarta.ws.rs.QueryParam("value") T value) { return ""; }
                        }

                        @RequiresPolicy(PolicyTypes.ActionOnlyPolicy.class)
                        @Path("/generic-ancestor-method-policy")
                        public class GenericAncestorMethodPolicy extends GenericAncestorBase<String> {}
                        """),
                Arguments.of(new SamePackageActionReplaces(), "SamePackageActionReplaces", """
                        import dev.vertique.security.authz.AccessPolicy;
                        import dev.vertique.security.authz.RequiresAction;
                        import dev.vertique.security.authz.RequiresPolicy;

                        class PolicyTypes {
                            @jakarta.annotation.security.RolesAllowed("admin")
                            public interface TypeAdminPolicy extends AccessPolicy {}

                            @RequiresAction("hello.greeting.read")
                            public interface MethodActionPolicy extends AccessPolicy {}
                        }

                        class SamePackageActionBase {
                            @RequiresPolicy(PolicyTypes.MethodActionPolicy.class)
                            String get() { return ""; }
                        }

                        @RequiresPolicy(PolicyTypes.TypeAdminPolicy.class)
                        @Path("/same-package-action")
                        public class SamePackageActionReplaces extends SamePackageActionBase {
                            @GET
                            @Override
                            public String get() { return ""; }
                        }
                        """));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("cases")
    @DisplayName("generated SecurityPolicy equals the reflective one")
    @SuppressWarnings("unchecked")
    void generatedPolicyEqualsReflective(Object reflectiveResource, String simpleName, String body) throws Exception {
        ResourceMethodMeta reflective =
                new JaxRsRouteRegistrar().scanResource(reflectiveResource).get(0);
        assertNull(reflective.executionPlan(), "the nested fixture must take the reflective path");

        String fqn = PKG + "." + simpleName;
        var resourceSource = SourceFiles.inline(fqn, """
                package %s;

                import dev.vertique.security.authz.Authorized;
                import jakarta.annotation.security.RolesAllowed;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                %s""".formatted(PKG, body));
        var result = "ForeignPackageDoesNotReplace".equals(simpleName)
                ? ProcessorTestHarness.run(new JaxRsPipelineProcessor(), resourceSource, foreignHiddenBase())
                : ProcessorTestHarness.run(new JaxRsPipelineProcessor(), resourceSource);
        result.assertSuccess();
        if ("InheritedInterfacePolicy".equals(simpleName)
                || "MethodReplacesActionPolicy".equals(simpleName)
                || "HiddenMethodDoesNotReplace".equals(simpleName)
                || "HiddenStaticDoesNotReplace".equals(simpleName)
                || "ForeignPackageDoesNotReplace".equals(simpleName)
                || "GenericMethodPolicy".equals(simpleName)
                || "GenericAncestorMethodPolicy".equals(simpleName)) {
            assertEquals(
                    new SecurityPolicy.Constrained(List.of("admin"), List.of(), false),
                    reflective.securityPolicy(),
                    "the selected policy must be the enforced policy");
        }
        if ("ActionReplacesRolesPolicy".equals(simpleName) || "SamePackageActionReplaces".equals(simpleName)) {
            assertEquals(
                    new SecurityPolicy.None(),
                    reflective.securityPolicy(),
                    "an action-only method policy replaces the type role policy");
            assertEquals(
                    Optional.of(ActionRef.parse("hello.greeting.read")),
                    new RequiresActionResolver().resolve(reflective.methodAnnotations(), reflective.classAnnotations()),
                    "the method action must be installed from the selected policy");
        }
        Object instance = result.generatedClassLoader()
                .loadClass(fqn)
                .getDeclaredConstructor()
                .newInstance();
        Class<?> descriptorClass = result.loadGeneratedClass(fqn + "_JaxRsDescriptor");
        Object descriptor = descriptorClass.getDeclaredConstructor().newInstance();
        List<ResourceMethodMeta> generated = (List<ResourceMethodMeta>) descriptorClass
                .getMethod("describe", Object.class, GeneratedJaxRsDescriptorSupport.class, List.class)
                .invoke(descriptor, instance, new GeneratedJaxRsDescriptorSupport(), new ArrayList<>());

        assertEquals(1, generated.size());
        assertEquals(reflective.securityPolicy(), generated.get(0).securityPolicy());
        RequiresActionResolver actions = new RequiresActionResolver();
        assertEquals(
                actions.resolve(reflective.methodAnnotations(), reflective.classAnnotations()),
                actions.resolve(
                        generated.get(0).methodAnnotations(), generated.get(0).classAnnotations()),
                "the generated annotation list must carry the same action");
    }

    private static JavaFileObject foreignHiddenBase() {
        return SourceFiles.inline("dev.vertique.test.hidden.ForeignHiddenBase", """
                package dev.vertique.test.hidden;

                import dev.vertique.security.authz.AccessPolicy;
                import dev.vertique.security.authz.RequiresPolicy;
                import jakarta.annotation.security.PermitAll;

                public class ForeignHiddenBase {
                    @PermitAll
                    public interface OpenPolicy extends AccessPolicy {}

                    @RequiresPolicy(OpenPolicy.class)
                    String get() { return ""; }
                }
                """);
    }
}
