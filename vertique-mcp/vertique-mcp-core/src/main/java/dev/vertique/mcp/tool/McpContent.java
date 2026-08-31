// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import jakarta.annotation.Nullable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.Objects;

/** Standard MCP content blocks that can appear in one complete tool result. */
public sealed interface McpContent
        permits McpContent.Text,
                McpContent.Image,
                McpContent.Audio,
                McpContent.ResourceLink,
                McpContent.EmbeddedResource {

    /** A UTF-8 text content block. */
    record Text(String text) implements McpContent {
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /** A base64-encoded image content block. */
    record Image(String data, String mimeType) implements McpContent {
        public Image {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(mimeType, "mimeType");
            Base64.getDecoder().decode(data);
        }
    }

    /** A base64-encoded audio content block. */
    record Audio(String data, String mimeType) implements McpContent {
        public Audio {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(mimeType, "mimeType");
            Base64.getDecoder().decode(data);
        }
    }

    /** A link to a resource that the client may fetch separately. */
    record ResourceLink(
            String uri,
            String name,
            @Nullable String title,
            @Nullable String description,
            @Nullable String mimeType) implements McpContent {
        public ResourceLink {
            validateUri(uri);
            Objects.requireNonNull(name, "name");
        }

        public ResourceLink(String uri, String name) {
            this(uri, name, null, null, null);
        }
    }

    /** A resource embedded directly in the result. */
    record EmbeddedResource(Resource resource) implements McpContent {
        public EmbeddedResource {
            Objects.requireNonNull(resource, "resource");
        }
    }

    /** The text or blob payload of an embedded MCP resource. */
    sealed interface Resource permits TextResource, BlobResource {
        String uri();

        @Nullable
        String mimeType();
    }

    /** A text embedded resource. */
    record TextResource(String uri, @Nullable String mimeType, String text) implements Resource {
        public TextResource {
            validateUri(uri);
            Objects.requireNonNull(text, "text");
        }
    }

    /** A base64-encoded binary embedded resource. */
    record BlobResource(String uri, @Nullable String mimeType, String blob) implements Resource {
        public BlobResource {
            validateUri(uri);
            Objects.requireNonNull(blob, "blob");
            Base64.getDecoder().decode(blob);
        }
    }

    private static void validateUri(String uri) {
        Objects.requireNonNull(uri, "uri");
        if (uri.isEmpty()) {
            throw new IllegalArgumentException("uri must not be empty");
        }
        try {
            new URI(uri);
        } catch (URISyntaxException invalidUri) {
            throw new IllegalArgumentException("uri must be a valid URI", invalidUri);
        }
    }
}
