// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.test;

import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;
import java.util.List;

/**
 * Minimal RuntimeDelegate stub for rest-core tests that trigger Response
 * construction through JAX-RS exception constructors (e.g. {@code WebApplicationException}).
 */
public class StubRuntimeDelegate extends RuntimeDelegate {

    @Override
    public Response.ResponseBuilder createResponseBuilder() {
        return new StubResponseBuilder();
    }

    @Override
    public UriBuilder createUriBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Variant.VariantListBuilder createVariantListBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T createEndpoint(Application application, Class<T> endpointType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) {
        if (type == MediaType.class) {
            @SuppressWarnings("unchecked")
            HeaderDelegate<T> delegate = (HeaderDelegate<T>) new MediaTypeHeaderDelegate();
            return delegate;
        }
        if (type == EntityTag.class) {
            @SuppressWarnings("unchecked")
            HeaderDelegate<T> delegate = (HeaderDelegate<T>) new EntityTagHeaderDelegate();
            return delegate;
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public Link.Builder createLinkBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public EntityPart.Builder createEntityPartBuilder(String partName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public jakarta.ws.rs.SeBootstrap.Configuration.Builder createConfigurationBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.concurrent.CompletionStage<jakarta.ws.rs.SeBootstrap.Instance> bootstrap(
            Application application, jakarta.ws.rs.SeBootstrap.Configuration configuration) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.concurrent.CompletionStage<jakarta.ws.rs.SeBootstrap.Instance> bootstrap(
            Class<? extends Application> clazz, jakarta.ws.rs.SeBootstrap.Configuration configuration) {
        throw new UnsupportedOperationException();
    }

    private static class EntityTagHeaderDelegate implements HeaderDelegate<EntityTag> {
        @Override
        public EntityTag fromString(String value) {
            if (value == null) {
                throw new IllegalArgumentException("value==null");
            }
            String trimmed = value.trim();
            boolean weak = trimmed.startsWith("W/");
            String tagPart = weak ? trimmed.substring(2) : trimmed;
            if (tagPart.startsWith("\"") && tagPart.endsWith("\"")) {
                return new EntityTag(tagPart.substring(1, tagPart.length() - 1), weak);
            }
            return new EntityTag(tagPart, weak);
        }

        @Override
        public String toString(EntityTag value) {
            return value.isWeak() ? "W/\"" + value.getValue() + "\"" : "\"" + value.getValue() + "\"";
        }
    }

    private static class MediaTypeHeaderDelegate implements HeaderDelegate<MediaType> {
        @Override
        public MediaType fromString(String value) {
            return MediaType.valueOf(value);
        }

        @Override
        public String toString(MediaType value) {
            return value.toString();
        }
    }

    private static class StubResponseBuilder extends Response.ResponseBuilder {
        private int status;
        private Object entity;

        @Override
        public Response build() {
            return new StubResponse(status, entity);
        }

        @Override
        public Response.ResponseBuilder clone() {
            StubResponseBuilder b = new StubResponseBuilder();
            b.status = this.status;
            b.entity = this.entity;
            return b;
        }

        @Override
        public Response.ResponseBuilder status(int status) {
            this.status = status;
            return this;
        }

        @Override
        public Response.ResponseBuilder status(int status, String reasonPhrase) {
            this.status = status;
            return this;
        }

        @Override
        public Response.ResponseBuilder entity(Object entity) {
            this.entity = entity;
            return this;
        }

        @Override
        public Response.ResponseBuilder entity(Object entity, java.lang.annotation.Annotation[] annotations) {
            this.entity = entity;
            return this;
        }

        @Override
        public Response.ResponseBuilder allow(String... methods) {
            return this;
        }

        @Override
        public Response.ResponseBuilder allow(java.util.Set<String> methods) {
            return this;
        }

        @Override
        public Response.ResponseBuilder cacheControl(jakarta.ws.rs.core.CacheControl cacheControl) {
            return this;
        }

        @Override
        public Response.ResponseBuilder encoding(String encoding) {
            return this;
        }

        @Override
        public Response.ResponseBuilder header(String name, Object value) {
            return this;
        }

        @Override
        public Response.ResponseBuilder replaceAll(jakarta.ws.rs.core.MultivaluedMap<String, Object> headers) {
            return this;
        }

        @Override
        public Response.ResponseBuilder language(String language) {
            return this;
        }

        @Override
        public Response.ResponseBuilder language(java.util.Locale language) {
            return this;
        }

        @Override
        public Response.ResponseBuilder type(MediaType type) {
            return this;
        }

        @Override
        public Response.ResponseBuilder type(String type) {
            return this;
        }

        @Override
        public Response.ResponseBuilder variant(Variant variant) {
            return this;
        }

        @Override
        public Response.ResponseBuilder contentLocation(java.net.URI location) {
            return this;
        }

        @Override
        public Response.ResponseBuilder cookie(jakarta.ws.rs.core.NewCookie... cookies) {
            return this;
        }

        @Override
        public Response.ResponseBuilder expires(java.util.Date expires) {
            return this;
        }

        @Override
        public Response.ResponseBuilder lastModified(java.util.Date lastModified) {
            return this;
        }

        @Override
        public Response.ResponseBuilder location(java.net.URI location) {
            return this;
        }

        @Override
        public Response.ResponseBuilder tag(jakarta.ws.rs.core.EntityTag tag) {
            return this;
        }

        @Override
        public Response.ResponseBuilder tag(String tag) {
            return this;
        }

        @Override
        public Response.ResponseBuilder variants(Variant... variants) {
            return this;
        }

        @Override
        public Response.ResponseBuilder variants(List<Variant> variants) {
            return this;
        }

        @Override
        public Response.ResponseBuilder links(Link... links) {
            return this;
        }

        @Override
        public Response.ResponseBuilder link(java.net.URI uri, String rel) {
            return this;
        }

        @Override
        public Response.ResponseBuilder link(String uri, String rel) {
            return this;
        }
    }

    private static class StubResponse extends Response {
        private final int status;
        private final Object entity;

        StubResponse(int status, Object entity) {
            this.status = status;
            this.entity = entity;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public StatusType getStatusInfo() {
            return Status.fromStatusCode(status);
        }

        @Override
        public Object getEntity() {
            return entity;
        }

        @Override
        public <T> T readEntity(Class<T> entityType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T readEntity(jakarta.ws.rs.core.GenericType<T> entityType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T readEntity(Class<T> entityType, java.lang.annotation.Annotation[] annotations) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T readEntity(
                jakarta.ws.rs.core.GenericType<T> entityType, java.lang.annotation.Annotation[] annotations) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasEntity() {
            return entity != null;
        }

        @Override
        public boolean bufferEntity() {
            return false;
        }

        @Override
        public void close() {}

        @Override
        public MediaType getMediaType() {
            return null;
        }

        @Override
        public java.util.Locale getLanguage() {
            return null;
        }

        @Override
        public int getLength() {
            return -1;
        }

        @Override
        public java.util.Set<String> getAllowedMethods() {
            return java.util.Set.of();
        }

        @Override
        public java.util.Map<String, jakarta.ws.rs.core.NewCookie> getCookies() {
            return java.util.Map.of();
        }

        @Override
        public jakarta.ws.rs.core.EntityTag getEntityTag() {
            return null;
        }

        @Override
        public java.util.Date getDate() {
            return null;
        }

        @Override
        public java.util.Date getLastModified() {
            return null;
        }

        @Override
        public java.net.URI getLocation() {
            return null;
        }

        @Override
        public java.util.Set<Link> getLinks() {
            return java.util.Set.of();
        }

        @Override
        public boolean hasLink(String relation) {
            return false;
        }

        @Override
        public Link getLink(String relation) {
            return null;
        }

        @Override
        public Link.Builder getLinkBuilder(String relation) {
            return null;
        }

        @Override
        public jakarta.ws.rs.core.MultivaluedMap<String, Object> getMetadata() {
            return new jakarta.ws.rs.core.MultivaluedHashMap<>();
        }

        @Override
        public jakarta.ws.rs.core.MultivaluedMap<String, Object> getHeaders() {
            return new jakarta.ws.rs.core.MultivaluedHashMap<>();
        }

        @Override
        public jakarta.ws.rs.core.MultivaluedMap<String, String> getStringHeaders() {
            return new jakarta.ws.rs.core.MultivaluedHashMap<>();
        }

        @Override
        public String getHeaderString(String name) {
            return null;
        }
    }
}
