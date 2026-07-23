// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletionStage;

public class SimpleRuntimeDelegate extends RuntimeDelegate {

    @Override
    public Response.ResponseBuilder createResponseBuilder() {
        return new SimpleResponseBuilder();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (MediaType.class.isAssignableFrom(type)) {
            return (HeaderDelegate<T>) MEDIA_TYPE_DELEGATE;
        }
        if (CacheControl.class.isAssignableFrom(type)) {
            return (HeaderDelegate<T>) CACHE_CONTROL_DELEGATE;
        }
        if (EntityTag.class.isAssignableFrom(type)) {
            return (HeaderDelegate<T>) ENTITY_TAG_DELEGATE;
        }
        if (NewCookie.class.isAssignableFrom(type)) {
            return (HeaderDelegate<T>) NEW_COOKIE_DELEGATE;
        }
        return new HeaderDelegate<>() {
            @Override
            public T fromString(String value) {
                throw new UnsupportedOperationException("fromString not supported");
            }

            @Override
            public String toString(T value) {
                if (value == null) {
                    throw new IllegalArgumentException("value must not be null");
                }
                return value.toString();
            }
        };
    }

    private static final HeaderDelegate<MediaType> MEDIA_TYPE_DELEGATE = new HeaderDelegate<>() {
        @Override
        public MediaType fromString(String value) {
            if (value == null) {
                throw new IllegalArgumentException("value must not be null");
            }
            String[] parts = value.trim().split(";", 2);
            String[] typeParts = parts[0].trim().split("/", 2);
            if (typeParts.length != 2) {
                throw new IllegalArgumentException("Invalid media type: " + value);
            }
            String type = typeParts[0].trim();
            String subtype = typeParts[1].trim();
            if (parts.length == 1) {
                return new MediaType(type, subtype);
            }
            Map<String, String> params = new java.util.LinkedHashMap<>();
            for (String param : parts[1].split(";")) {
                String[] kv = param.trim().split("=", 2);
                if (kv.length == 2) {
                    params.put(kv[0].trim(), kv[1].trim());
                }
            }
            return new MediaType(type, subtype, params);
        }

        @Override
        public String toString(MediaType value) {
            if (value == null) {
                throw new IllegalArgumentException("value must not be null");
            }
            StringBuilder sb = new StringBuilder(value.getType()).append('/').append(value.getSubtype());
            for (Map.Entry<String, String> param : value.getParameters().entrySet()) {
                sb.append(';').append(param.getKey()).append('=').append(param.getValue());
            }
            return sb.toString();
        }
    };

    private static final HeaderDelegate<CacheControl> CACHE_CONTROL_DELEGATE = new HeaderDelegate<>() {
        @Override
        public CacheControl fromString(String value) {
            if (value == null) {
                throw new IllegalArgumentException("value must not be null");
            }
            CacheControl cc = new CacheControl();
            cc.setNoTransform(false); // default is true, but we only set if directive present
            for (String directive : splitRespectingQuotes(value, ',')) {
                String trimmed = directive.trim();
                int eqPos = trimmed.indexOf('=');
                String key = eqPos >= 0
                        ? trimmed.substring(0, eqPos).trim().toLowerCase(Locale.ROOT)
                        : trimmed.toLowerCase(Locale.ROOT);
                String rawVal = eqPos >= 0 ? trimmed.substring(eqPos + 1).trim() : null;

                if (key.equals("no-cache")) {
                    cc.setNoCache(true);
                    parseFieldNames(rawVal, cc.getNoCacheFields());
                } else if (key.equals("no-store")) {
                    cc.setNoStore(true);
                } else if (key.equals("no-transform")) {
                    cc.setNoTransform(true);
                } else if (key.equals("must-revalidate")) {
                    cc.setMustRevalidate(true);
                } else if (key.equals("proxy-revalidate")) {
                    cc.setProxyRevalidate(true);
                } else if (key.equals("private")) {
                    cc.setPrivate(true);
                    parseFieldNames(rawVal, cc.getPrivateFields());
                } else if (key.equals("max-age")) {
                    cc.setMaxAge(Integer.parseInt(rawVal));
                } else if (key.equals("s-maxage")) {
                    cc.setSMaxAge(Integer.parseInt(rawVal));
                } else if (!key.isEmpty()) {
                    // Cache extension — preserve original value case
                    if (rawVal != null) {
                        if (rawVal.startsWith("\"") && rawVal.endsWith("\"")) {
                            rawVal = unescapeQuotedPair(rawVal.substring(1, rawVal.length() - 1));
                        }
                        cc.getCacheExtension().put(key, rawVal);
                    } else {
                        cc.getCacheExtension().put(key, "");
                    }
                }
            }
            return cc;
        }

        @Override
        public String toString(CacheControl cc) {
            if (cc == null) throw new IllegalArgumentException("value must not be null");
            StringJoiner sj = new StringJoiner(", ");
            if (cc.isPrivate()) sj.add("private");
            if (cc.isNoCache()) sj.add("no-cache");
            if (cc.isNoStore()) sj.add("no-store");
            if (cc.isNoTransform()) sj.add("no-transform");
            if (cc.isMustRevalidate()) sj.add("must-revalidate");
            if (cc.isProxyRevalidate()) sj.add("proxy-revalidate");
            if (cc.getMaxAge() >= 0) sj.add("max-age=" + cc.getMaxAge());
            if (cc.getSMaxAge() >= 0) sj.add("s-maxage=" + cc.getSMaxAge());
            for (Map.Entry<String, String> ext : cc.getCacheExtension().entrySet()) {
                if (ext.getValue() != null && !ext.getValue().isEmpty()) {
                    sj.add(ext.getKey() + "=\"" + ext.getValue() + "\"");
                } else {
                    sj.add(ext.getKey());
                }
            }
            return sj.toString();
        }
    };

    private static final HeaderDelegate<EntityTag> ENTITY_TAG_DELEGATE = new HeaderDelegate<>() {
        @Override
        public EntityTag fromString(String value) {
            if (value == null) {
                throw new IllegalArgumentException("value must not be null");
            }
            String v = value.trim();
            boolean weak = v.startsWith("W/");
            if (weak) {
                v = v.substring(2);
            }
            if (v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            return new EntityTag(v, weak);
        }

        @Override
        public String toString(EntityTag tag) {
            if (tag == null) throw new IllegalArgumentException("value must not be null");
            String prefix = tag.isWeak() ? "W/" : "";
            return prefix + "\"" + tag.getValue() + "\"";
        }
    };

    private static final HeaderDelegate<NewCookie> NEW_COOKIE_DELEGATE = new HeaderDelegate<>() {
        @Override
        public NewCookie fromString(String value) {
            throw new UnsupportedOperationException("fromString not supported");
        }

        @Override
        public String toString(NewCookie cookie) {
            if (cookie == null) throw new IllegalArgumentException("value must not be null");
            StringBuilder sb = new StringBuilder();
            sb.append(cookie.getName()).append('=').append(cookie.getValue());
            if (cookie.getDomain() != null) sb.append("; Domain=").append(cookie.getDomain());
            if (cookie.getPath() != null) sb.append("; Path=").append(cookie.getPath());
            if (cookie.getMaxAge() >= 0) sb.append("; Max-Age=").append(cookie.getMaxAge());
            if (cookie.isSecure()) sb.append("; Secure");
            if (cookie.isHttpOnly()) sb.append("; HttpOnly");
            if (cookie.getSameSite() != null) {
                sb.append("; SameSite=")
                        .append(
                                switch (cookie.getSameSite()) {
                                    case NONE -> "None";
                                    case LAX -> "Lax";
                                    case STRICT -> "Strict";
                                });
            }
            if (cookie.getExpiry() != null) {
                sb.append("; Expires=")
                        .append(ZonedDateTime.ofInstant(cookie.getExpiry().toInstant(), ZoneOffset.UTC)
                                .format(DateTimeFormatter.RFC_1123_DATE_TIME));
            }
            return sb.toString();
        }
    };

    @Override
    public UriBuilder createUriBuilder() {
        return new SimpleUriBuilder();
    }

    @Override
    public Variant.VariantListBuilder createVariantListBuilder() {
        return new SimpleVariantListBuilder();
    }

    @Override
    public <T> T createEndpoint(Application application, Class<T> endpointType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Link.Builder createLinkBuilder() {
        return new SimpleLinkBuilder();
    }

    @Override
    public SeBootstrap.Configuration.Builder createConfigurationBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(
            Application application, SeBootstrap.Configuration configuration) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(
            Class<? extends Application> clazz, SeBootstrap.Configuration configuration) {
        throw new UnsupportedOperationException();
    }

    @Override
    public EntityPart.Builder createEntityPartBuilder(String partName) {
        throw new UnsupportedOperationException();
    }

    /** Parses an optional comma-separated, quoted field-name list (e.g. {@code "Authorization, Set-Cookie"}) into the target list. */
    private static void parseFieldNames(String rawVal, List<String> target) {
        if (rawVal == null) {
            return;
        }
        String v = rawVal.trim();
        if (v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        for (String field : v.split(",")) {
            String trimmed = field.trim();
            if (!trimmed.isEmpty()) {
                target.add(trimmed);
            }
        }
    }

    /**
     * Splits {@code input} on {@code delimiter} while respecting double-quoted strings
     * and backslash escapes inside them (e.g. {@code \"} does not end the quoted region).
     */
    static List<String> splitRespectingQuotes(String input, char delimiter) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && inQuotes && i + 1 < input.length()) {
                // Escaped character inside quotes — pass through both chars
                current.append(c);
                i++;
                current.append(input.charAt(i));
            } else if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == delimiter && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** Unescapes RFC 7230 quoted-pair sequences: {@code \"} → {@code "} and {@code \\} → {@code \}. */
    static String unescapeQuotedPair(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                i++;
                sb.append(value.charAt(i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
