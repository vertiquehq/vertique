// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import jakarta.ws.rs.core.Response;

class SimpleStatusType implements Response.StatusType {

    private final int statusCode;
    private final String reasonPhrase;

    SimpleStatusType(int statusCode, String reasonPhrase) {
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
    }

    SimpleStatusType(int statusCode) {
        this(statusCode, null);
    }

    @Override
    public int getStatusCode() {
        return statusCode;
    }

    @Override
    public Response.Status.Family getFamily() {
        return Response.Status.Family.familyOf(statusCode);
    }

    @Override
    public String getReasonPhrase() {
        if (reasonPhrase != null) {
            return reasonPhrase;
        }
        return switch (statusCode) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 409 -> "Conflict";
            case 413 -> "Payload Too Large";
            case 415 -> "Unsupported Media Type";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "Unknown";
        };
    }
}
