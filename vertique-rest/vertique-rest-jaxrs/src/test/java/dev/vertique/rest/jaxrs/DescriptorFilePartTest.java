// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.rest.core.request.FilePart;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.ext.web.FileUpload;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.EntityPart;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies projection of multipart file constraints into the public operation descriptor. */
class DescriptorFilePartTest {

    @Path("/files")
    static class FileResource {

        @POST
        @Consumes("multipart/form-data")
        @Operation(operationId = "uploadAvatar")
        String upload(
                @FormParam("avatar")
                        @FilePart(
                                allowedTypes = {"image/png"},
                                maxSizeBytes = 4096)
                        FileUpload avatar) {
            return "ok";
        }
    }

    @Path("/aggregate-files")
    static class AggregateFileResource {

        @POST
        @Consumes("multipart/form-data")
        String upload(
                @FilePart(
                                allowedTypes = {"image/png", "application/pdf"},
                                maxSizeBytes = 8192)
                        List<FileUpload> files) {
            return "ok";
        }
    }

    @Path("/unconstrained-files")
    static class UnconstrainedFileResource {

        @POST
        @Consumes("multipart/form-data")
        String upload(
                @FormParam("avatar") FileUpload avatar,
                @FormParam("attachments") List<FileUpload> attachments,
                List<FileUpload> files) {
            return "ok";
        }
    }

    @Path("/entity-parts")
    static class EntityPartResource {

        @POST
        @Consumes("multipart/form-data")
        String upload(
                @FormParam("document") EntityPart document,
                @FormParam("documents") List<EntityPart> documents,
                List<EntityPart> parts) {
            return "ok";
        }
    }

    @Path("/projected-file")
    static class ParametersProjectionResource {

        @POST
        @Consumes("multipart/form-data")
        String upload(@FormParam("avatar") @FilePart(allowedTypes = "image/png") FileUpload avatar) {
            return "ok";
        }
    }

    @Path("/canonical-file")
    static class CanonicalFileResource {

        @POST
        @Consumes("multipart/form-data")
        String upload(@FormParam("avatar") @FilePart(allowedTypes = {"Image/PNG", "APPLICATION/*"}) FileUpload avatar) {
            return "ok";
        }
    }

    @Test
    @DisplayName("A named @FormParam FileUpload carries its @FilePart constraints into the descriptor")
    void namedFormFilePartCarriesConstraints() {
        ResourceMethodMeta meta =
                new JaxRsRouteRegistrar().scanResource(new FileResource()).getFirst();

        JaxRsOperationDescriptor descriptor = ResourceMethodMetaToDescriptorAdapter.adapt(meta);

        assertEquals(List.of(new FilePartDescriptor("avatar", List.of("image/png"), 4096)), descriptor.fileParts());
    }

    @Test
    @DisplayName("An aggregate List<FileUpload> is represented by a null part name")
    void aggregateFileUploadListYieldsNullPartName() {
        ResourceMethodMeta meta = new JaxRsRouteRegistrar()
                .scanResource(new AggregateFileResource())
                .getFirst();

        JaxRsOperationDescriptor descriptor = ResourceMethodMetaToDescriptorAdapter.adapt(meta);

        assertEquals(
                List.of(new FilePartDescriptor(null, List.of("image/png", "application/pdf"), 8192)),
                descriptor.fileParts());
    }

    @Test
    @DisplayName("FileUpload parameters without @FilePart yield unconstrained descriptors")
    void unannotatedFileParamsYieldUnconstrainedMetas() {
        ResourceMethodMeta meta = new JaxRsRouteRegistrar()
                .scanResource(new UnconstrainedFileResource())
                .getFirst();

        JaxRsOperationDescriptor descriptor = ResourceMethodMetaToDescriptorAdapter.adapt(meta);

        assertEquals(
                List.of(
                        new FilePartDescriptor("avatar", List.of(), -1),
                        new FilePartDescriptor("attachments", List.of(), -1),
                        new FilePartDescriptor(null, List.of(), -1)),
                descriptor.fileParts());
    }

    @Test
    @DisplayName("EntityPart parameters yield unconstrained descriptors")
    void entityPartParamsYieldUnconstrainedMetas() {
        ResourceMethodMeta meta =
                new JaxRsRouteRegistrar().scanResource(new EntityPartResource()).getFirst();

        JaxRsOperationDescriptor descriptor = ResourceMethodMetaToDescriptorAdapter.adapt(meta);

        assertEquals(
                List.of(
                        new FilePartDescriptor("document", List.of(), -1),
                        new FilePartDescriptor("documents", List.of(), -1),
                        new FilePartDescriptor(null, List.of(), -1)),
                descriptor.fileParts());
    }

    @Test
    @DisplayName("File-part projection leaves the named FORM parameter projection unchanged")
    void parametersProjectionUnchangedByFileParts() {
        ResourceMethodMeta meta = new JaxRsRouteRegistrar()
                .scanResource(new ParametersProjectionResource())
                .getFirst();

        JaxRsOperationDescriptor descriptor = ResourceMethodMetaToDescriptorAdapter.adapt(meta);

        assertEquals(1, descriptor.parameters().size());
        ParamDescriptor parameter = descriptor.parameters().getFirst();
        assertEquals("avatar", parameter.name());
        assertEquals(ParamLocation.FORM, parameter.location());
        assertEquals(FileUpload.class, parameter.type());
        assertNull(parameter.componentType());
        assertEquals(List.of(new FilePartDescriptor("avatar", List.of("image/png"), -1)), descriptor.fileParts());
    }

    @Test
    @DisplayName("Allowed media types are canonicalized when the descriptor is created")
    void allowedTypesCanonicalizedAtDescriptorCreation() {
        ResourceMethodMeta meta = new JaxRsRouteRegistrar()
                .scanResource(new CanonicalFileResource())
                .getFirst();

        JaxRsOperationDescriptor descriptor = ResourceMethodMetaToDescriptorAdapter.adapt(meta);

        assertEquals(
                List.of(new FilePartDescriptor("avatar", List.of("image/png", "application/*"), -1)),
                descriptor.fileParts());
    }
}
