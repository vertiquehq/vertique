// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.internal;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * INTERNAL, framework use only. Decides whether effective {@link WebClientOptions} weaken TLS. This
 * type is outside the compatibility promise of the module.
 *
 * <p>Vert.x ignores keys it does not know, such as a nested {@code sslOptions} object, so the
 * effective options alone cannot show every weakening a configuration bag asks for;
 * {@link #isInsecure(JsonObject)} also scans the bag itself at every depth.
 *
 * <p>The options count as insecure when they trust all certificates, skip host verification (the flag
 * is off or the hostname verification algorithm is blank), or enable a TLS protocol below 1.2. The
 * check reads the effective options, so it holds whichever key or nesting set them.
 */
public final class WebClientOptionsHardening {

    private static final Pattern TLS = Pattern.compile("TLSv(\\d{1,3})(?:\\.(\\d{1,3}))?");

    private WebClientOptionsHardening() {}

    /**
     * Tells whether the options weaken TLS.
     *
     * @param options the effective options
     * @return {@code true} when they trust all certificates, skip host verification or enable TLS
     *     below 1.2
     */
    public static boolean isInsecure(WebClientOptions options) {
        if (options.isTrustAll() || !options.isVerifyHost()) {
            return true;
        }
        ClientSSLOptions ssl = options.getSslOptions();
        if (ssl == null) {
            return false;
        }
        String algorithm = ssl.getHostnameVerificationAlgorithm();
        if (ssl.isTrustAll() || algorithm != null && algorithm.isBlank()) {
            return true;
        }
        Set<String> protocols = ssl.getEnabledSecureTransportProtocols();
        if (protocols != null) {
            for (String protocol : protocols) {
                if (belowTls12(protocol)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Tells whether a configuration bag asks for weakened TLS under any key path: {@code trustAll}
     * true, {@code verifyHost} false, a blank {@code hostnameVerificationAlgorithm}, or an
     * {@code enabledSecureTransportProtocols} entry below TLS 1.2, at any depth.
     *
     * @param bag the configuration bag
     * @return {@code true} when any such setting is present
     */
    public static boolean isInsecure(JsonObject bag) {
        for (String key : bag.fieldNames()) {
            Object value = bag.getValue(key);
            switch (key) {
                case "trustAll" -> {
                    if (isTrue(value)) {
                        return true;
                    }
                }
                case "verifyHost" -> {
                    if (isFalse(value)) {
                        return true;
                    }
                }
                case "hostnameVerificationAlgorithm" -> {
                    if (value instanceof String text && text.isBlank()) {
                        return true;
                    }
                }
                case "enabledSecureTransportProtocols" -> {
                    if (weakProtocols(value)) {
                        return true;
                    }
                }
                default -> {
                    // not a TLS setting itself
                }
            }
            if (nestedInsecure(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nestedInsecure(Object value) {
        if (value instanceof JsonObject object) {
            return isInsecure(object);
        }
        if (value instanceof JsonArray array) {
            for (Object element : array) {
                if (element instanceof JsonObject object ? isInsecure(object) : nestedInsecure(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean weakProtocols(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof JsonArray array) {
            for (Object protocol : array) {
                if (!(protocol instanceof String text) || belowTls12(text)) {
                    return true;
                }
            }
            return false;
        }
        return !(value instanceof String text) || belowTls12(text);
    }

    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean isFalse(Object value) {
        return Boolean.FALSE.equals(value) || "false".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean belowTls12(String protocol) {
        Matcher match = TLS.matcher(protocol);
        if (!match.matches()) {
            return true;
        }
        int major = Integer.parseInt(match.group(1));
        int minor = match.group(2) == null ? 0 : Integer.parseInt(match.group(2));
        return major < 1 || major == 1 && minor < 2;
    }
}
