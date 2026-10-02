// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.document;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import dev.vertique.json.schema.RedactionManifest;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputKey;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.publication.ResponseShape;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders the {@link Snapshot} of a mount publication.
 *
 * <p>Each operation is rendered to one canonical UTF-8 text and reduced to its SHA-256 digest, as 64
 * lowercase hexadecimal characters. Every value in the text is delimited by its own length, so no
 * two lists of values render alike. Schemas are rendered with the keys of every object in sorted
 * order, and the parameter schemas in order of location and then name, so the order in which a
 * publication happened to be populated does not change the digest. The operation descriptor, the
 * resource class of a response shape, and the identity of the body provenance are never read.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 */
public final class SnapshotRenderer {

    private static final String HASH_ALGORITHM = "SHA-256";

    private SnapshotRenderer() {}

    /**
     * Renders the snapshot of a publication.
     *
     * @param publication the mount publication
     * @return the snapshot of the publication
     */
    public static Snapshot render(MountPublication publication) {
        Snapshot.MountPart mountPart = new Snapshot.MountPart(
                publication.mountPath(),
                publication.strategyId(),
                publication.applicationName() == null ? "" : publication.applicationName(),
                publication.declaringType() == null
                        ? ""
                        : publication.declaringType().getName());
        Map<String, String> digests = new TreeMap<>();
        for (OperationPublication operation : publication.operations()) {
            digests.put(operation.operationId(), digest(renderOperation(operation)));
        }
        return new Snapshot(mountPart, new TreeMap<>(digests));
    }

    private static String renderOperation(OperationPublication operation) {
        Canonical text = new Canonical();
        text.text(operation.operationId());
        text.text(operation.httpMethod());
        text.text(operation.jaxRsPathTemplate());
        text.text(operation.vertxRouteValue());
        text.flag(operation.vertxRouteIsRegex());
        text.text(operation.effectivePolicy().toString());
        text.count(operation.securityRequirementSets().size());
        operation.securityRequirementSets().forEach(set -> text.text(set.toString()));
        text.flag(operation.requiresAction());
        OperationDetail detail = operation.detail();
        if (detail == null) {
            text.absent();
        } else {
            text.present();
            renderDetail(text, detail);
        }
        return text.toString();
    }

    private static void renderDetail(Canonical text, OperationDetail detail) {
        text.text(detail.profileId());
        text.flag(detail.gateInstalled());
        JsonObject body = detail.schemas().body();
        if (body == null) {
            text.absent();
        } else {
            text.present();
            text.text(canonicalJson(body));
        }
        text.nullable(provenance(detail.schemas().bodyProvenance()));
        Map<InputKey, JsonObject> parameters = detail.schemas().parameters();
        List<InputKey> keys = parameters.keySet().stream()
                .sorted(Comparator.comparing((InputKey key) -> key.location().name())
                        .thenComparing(InputKey::name))
                .toList();
        text.count(keys.size());
        for (InputKey key : keys) {
            text.text(key.location().name());
            text.text(key.name());
            text.text(canonicalJson(parameters.get(key)));
        }
        text.count(detail.inputs().size());
        detail.inputs().forEach(input -> renderInput(text, input));
        renderResponse(text, detail.response());
    }

    private static void renderInput(Canonical text, InputBinding input) {
        text.text(input.origin().name());
        text.nullable(input.location() == null ? null : input.location().name());
        text.nullable(input.name());
        text.text(input.type().getTypeName());
        text.nullable(input.defaultValue());
        text.text(input.requiredness().name());
        text.flag(input.hidden());
        text.flag(input.schemaEnforced());
        text.nullable(
                input.methodParameterIndex() == null
                        ? null
                        : input.methodParameterIndex().toString());
        text.nullable(
                input.compositeType() == null ? null : input.compositeType().getName());
        text.count(input.annotations().size());
        input.annotations().forEach(annotation -> text.text(annotation.toString()));
    }

    private static void renderResponse(Canonical text, ResponseShape response) {
        text.text(response.genericReturnType().getTypeName());
        text.flag(response.returnsFuture());
        text.flag(response.returnsVoid());
        text.count(response.produces().size());
        response.produces().forEach(text::text);
        text.text(response.outputProfileId());
    }

    /** The digest of a redaction manifest, the class name of any other provenance, or {@code null}. */
    private static @Nullable String provenance(@Nullable Object provenance) {
        if (provenance == null) {
            return null;
        }
        if (provenance instanceof RedactionManifest manifest) {
            return "manifest:" + manifest.digest();
        }
        return "class:" + provenance.getClass().getName();
    }

    /** Renders a schema with the keys of every object in sorted order. */
    private static String canonicalJson(Object value) {
        StringBuilder out = new StringBuilder();
        appendJson(out, value);
        return out.toString();
    }

    private static void appendJson(StringBuilder out, @Nullable Object value) {
        switch (value) {
            case null -> out.append("null");
            case JsonObject object -> appendObject(out, object.getMap());
            case Map<?, ?> map -> appendObject(out, map);
            case JsonArray array -> appendArray(out, array.getList());
            case List<?> list -> appendArray(out, list);
            case String string -> appendString(out, string);
            case Number number -> out.append(number);
            case Boolean bool -> out.append(bool);
            default -> {
                out.append('<').append(value.getClass().getName()).append('>');
                appendString(out, value.toString());
            }
        }
    }

    private static void appendObject(StringBuilder out, Map<?, ?> map) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        map.forEach((key, entry) -> sorted.put(String.valueOf(key), entry));
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendString(out, entry.getKey());
            out.append(':');
            appendJson(out, entry.getValue());
        }
        out.append('}');
    }

    private static void appendArray(StringBuilder out, List<?> list) {
        out.append('[');
        boolean first = true;
        for (Object element : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendJson(out, element);
        }
        out.append(']');
    }

    private static void appendString(StringBuilder out, String string) {
        out.append('"');
        JsonStringEncoder.getInstance().quoteAsString(string, out);
        out.append('"');
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance(HASH_ALGORITHM).digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " is not available", e);
        }
    }

    /**
     * Builds an unambiguous text: every string is written as its length, a colon, and its value, and
     * every other value as a fixed marker, so the boundary between two values never depends on their
     * content.
     */
    private static final class Canonical {
        private final StringBuilder out = new StringBuilder();

        void text(String value) {
            out.append(value.length()).append(':').append(value).append(';');
        }

        void nullable(@Nullable String value) {
            if (value == null) {
                absent();
            } else {
                present();
                text(value);
            }
        }

        void flag(boolean value) {
            out.append(value ? 'T' : 'F').append(';');
        }

        void count(int value) {
            out.append('#').append(value).append(';');
        }

        void absent() {
            out.append("-;");
        }

        void present() {
            out.append("+;");
        }

        @Override
        public String toString() {
            return out.toString();
        }
    }
}
