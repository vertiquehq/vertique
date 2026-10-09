// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import dev.vertique.rest.core.request.HeaderElement;
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
import java.util.LinkedHashMap;
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
            List<String> segments = HeaderElement.splitOutsideQuotes(value.trim(), ';');
            if (segments == null) {
                throw new IllegalArgumentException("Invalid media type: " + value);
            }
            String[] typeParts = segments.get(0).trim().split("/", 2);
            if (typeParts.length != 2) {
                throw new IllegalArgumentException("Invalid media type: " + value);
            }
            String type = typeParts[0].trim();
            String subtype = typeParts[1].trim();
            if (segments.size() == 1) {
                return new MediaType(type, subtype);
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (String param : segments.subList(1, segments.size())) {
                String[] kv = param.trim().split("=", 2);
                if (kv.length == 2) {
                    String paramValue = HeaderElement.unquote(kv[1].trim());
                    if (paramValue == null) {
                        throw new IllegalArgumentException("Invalid media type: " + value);
                    }
                    params.put(kv[0].trim(), paramValue);
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
                String paramValue = param.getValue();
                sb.append(';')
                        .append(param.getKey())
                        .append('=')
                        .append(isToken(paramValue) ? paramValue : quoted(paramValue));
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
            List<String> directives = HeaderElement.splitOutsideQuotes(value, ',');
            if (directives == null) {
                throw new IllegalArgumentException("Invalid cache control value: " + value);
            }
            for (String directive : directives) {
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
                        String extensionValue = HeaderElement.unquote(rawVal);
                        if (extensionValue == null) {
                            throw new IllegalArgumentException("Invalid cache control value: " + value);
                        }
                        cc.getCacheExtension().put(key, extensionValue);
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
                    sj.add(ext.getKey() + "=" + quoted(ext.getValue()));
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
        String v = HeaderElement.unquote(rawVal.trim());
        if (v == null) {
            throw new IllegalArgumentException("Invalid quoted field-name list: " + rawVal);
        }
        for (String field : v.split(",")) {
            String trimmed = field.trim();
            if (!trimmed.isEmpty()) {
                target.add(trimmed);
            }
        }
    }

    /** Returns whether {@code value} is a non-empty RFC 9110 token, which needs no quoting as a parameter value. */
    private static boolean isToken(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean tokenChar = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!tokenChar) {
                return false;
            }
        }
        return true;
    }

    /** Writes {@code value} as a quoted-string: backslashes and double quotes are escaped, bare CR and LF are dropped. */
    private static String quoted(String value) {
        return "\"" + HeaderUtils.escapeQuoted(value) + "\"";
    }
}
