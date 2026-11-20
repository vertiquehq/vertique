// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.meta;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.rest.client.ExpectedStatus;
import io.vertx.core.Future;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Generic super-interface inheritance in {@link ClientInterfaceScanner}: a client interface reads
 * the annotations of the generic declarations it inherits or overrides, and the types its hierarchy
 * binds.
 */
class ClientInterfaceScannerInheritanceTest {

    record User(String name) {}

    interface Crud<T, ID> {
        @GET
        @Path("/{id}")
        Future<T> get(@PathParam("id") ID id);

        @POST
        Future<T> create(T body);

        @GET
        Future<List<T>> list(@QueryParam("page") @DefaultValue("1") Integer page);

        @DELETE
        @Path("/{id}")
        Future<Void> delete(@PathParam("id") ID id);
    }

    @Path("/users")
    interface InheritingClient extends Crud<User, String> {}

    @Path("/users")
    interface RedeclaringClient extends Crud<User, String> {
        @Override
        Future<Void> delete(String id);
    }

    @Path("/users")
    interface OverridingClient extends Crud<User, String> {
        @Override
        @POST
        @ExpectedStatus(202)
        @Path("/{id}/remove")
        Future<Void> delete(String id);
    }

    interface Mid<X> extends Crud<User, X> {}

    @Path("/users")
    interface MultiHopClient extends Mid<Long> {
        @Override
        Future<User> get(Long id);
    }

    interface SiblingA {
        @GET
        @Path("/shared")
        Future<String> shared();
    }

    interface SiblingB {
        @GET
        @Path("/shared")
        Future<String> shared();
    }

    @Path("/diamond")
    interface DiamondClient extends SiblingA, SiblingB {}

    interface Plain {
        @GET
        @Path("/ping")
        Future<String> ping();
    }

    @Path("/plain")
    interface PlainChild extends Plain {}

    private static Method method(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return type.getMethod(name, parameterTypes);
    }

    @Test
    @DisplayName("an inherited generic declaration resolves its response, body and parameter types")
    void inheritedDeclarationResolvesBoundTypes() throws Exception {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(InheritingClient.class);

        ClientMethodMeta get = metas.get(method(Crud.class, "get", Object.class));
        assertThat(get.httpMethod()).isEqualTo("GET");
        assertThat(get.pathTemplate()).isEqualTo("/users/{id}");
        assertThat(get.responseType()).isEqualTo(User.class);
        assertThat(get.params().get(0).type()).isEqualTo(String.class);
        assertThat(get.params().get(0).name()).isEqualTo("id");

        ClientMethodMeta create = metas.get(method(Crud.class, "create", Object.class));
        assertThat(create.params().get(0).type()).isEqualTo(User.class);
        assertThat(create.params().get(0).source()).isEqualTo(ClientParamMeta.ParamSource.BODY);

        ClientMethodMeta list = metas.get(method(Crud.class, "list", Integer.class));
        assertThat(list.responseType()).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) list.responseType()).getActualTypeArguments())
                .containsExactly(User.class);
        assertThat(list.params().get(0).defaultValue()).isEqualTo("1");
        assertThat(metas).hasSize(4);
    }

    @Test
    @DisplayName("a redeclared method inherits the declaration's annotations and both Methods share the operation")
    void redeclarationInheritsAnnotations() throws Exception {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(RedeclaringClient.class);

        Method redeclared = method(RedeclaringClient.class, "delete", String.class);
        Method declaration = method(Crud.class, "delete", Object.class);
        ClientMethodMeta meta = metas.get(redeclared);

        assertThat(meta).isNotNull();
        assertThat(meta.httpMethod()).isEqualTo("DELETE");
        assertThat(meta.pathTemplate()).isEqualTo("/users/{id}");
        assertThat(meta.params().get(0).source()).isEqualTo(ClientParamMeta.ParamSource.PATH);
        assertThat(meta.params().get(0).name()).isEqualTo("id");
        assertThat(meta.params().get(0).type()).isEqualTo(String.class);
        assertThat(metas.get(declaration)).isSameAs(meta);
        // the bridge the compiler adds to the redeclaration is what the JDK proxy receives for a call
        // through Crud<String, String>
        Method bridge = method(RedeclaringClient.class, "delete", Object.class);
        assertThat(bridge.isBridge()).isTrue();
        assertThat(metas.get(bridge)).isSameAs(meta);
        assertThat(metas).hasSize(6);
    }

    @Test
    @DisplayName("the redeclaring method's own annotations win over the declaration's")
    void nearestDeclarationWins() throws Exception {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(OverridingClient.class);

        ClientMethodMeta meta = metas.get(method(OverridingClient.class, "delete", String.class));

        assertThat(meta.httpMethod()).isEqualTo("POST");
        assertThat(meta.pathTemplate()).isEqualTo("/users/{id}/remove");
        assertThat(meta.expectation()).isNotNull();
        assertThat(metas.get(method(Crud.class, "delete", Object.class))).isSameAs(meta);
    }

    @Test
    @DisplayName("variables forwarded through an intermediate interface resolve to the client's binding")
    void multiHopResolution() throws Exception {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(MultiHopClient.class);

        ClientMethodMeta get = metas.get(method(MultiHopClient.class, "get", Long.class));
        assertThat(get.params().get(0).type()).isEqualTo(Long.class);
        assertThat(get.params().get(0).name()).isEqualTo("id");
        assertThat(get.responseType()).isEqualTo(User.class);

        ClientMethodMeta delete = metas.get(method(Crud.class, "delete", Object.class));
        assertThat(delete.params().get(0).type()).isEqualTo(Long.class);
    }

    @Test
    @DisplayName("a non-generic inherited method is an operation of the client under the client's path")
    void plainInheritedMethod() throws Exception {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(PlainChild.class);

        ClientMethodMeta ping = metas.get(method(Plain.class, "ping"));
        assertThat(ping.pathTemplate()).isEqualTo("/plain/ping");
        assertThat(metas).hasSize(1);
    }

    @Test
    @DisplayName("override-equivalent declarations of unrelated interfaces are separate operations")
    void unrelatedSiblingsDoNotOverrideEachOther() throws Exception {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(DiamondClient.class);

        assertThat(metas.get(method(SiblingA.class, "shared"))).isNotNull();
        assertThat(metas.get(method(SiblingB.class, "shared"))).isNotNull();
        assertThat(metas.get(method(SiblingA.class, "shared")).pathTemplate()).isEqualTo("/diamond/shared");
    }

    @Test
    @DisplayName("method metadata sees the annotations inherited from the declarations it overrides")
    void methodMetadataSeesInheritedAnnotations() throws Exception {
        Map<Method, ClientMethodMeta> metas = ClientInterfaceScanner.scan(OverridingClient.class);

        ClientMethodMeta meta = metas.get(method(OverridingClient.class, "delete", String.class));

        assertThat(meta.methodMetadata().hasAnnotation(DELETE.class)).isTrue();
        assertThat(meta.methodMetadata().findAnnotation(ExpectedStatus.class)).isPresent();
        assertThat(meta.methodMetadata().hasAnnotation(Path.class)).isTrue();
    }
}
