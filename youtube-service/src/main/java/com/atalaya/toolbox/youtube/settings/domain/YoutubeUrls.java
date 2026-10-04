package com.atalaya.toolbox.youtube.settings.domain;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class YoutubeUrls {
    private YoutubeUrls() {}

    public static String normalizeVideoUrl(String url) {
        URI uri = validatedUri(url);
        String videoId = isShortYoutubeHost(uri.getHost())
            ? uri.getRawPath().replaceFirst("^/", "").split("/", 2)[0]
            : uniqueQueryValue(uri.getRawQuery(), "v");
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("La URL de descarga debe identificar un video de YouTube (parámetro v).");
        }
        return removeQueryParameter(uri, "list");
    }

    public static String normalizePlaylistUrl(String url) {
        URI uri = validatedUri(url);
        String playlistId = uniqueQueryValue(uri.getRawQuery(), "list");
        if (playlistId == null || !playlistId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("La URL de preparación debe identificar una playlist de YouTube (parámetro list).");
        }
        return removeQueryParameter(uri, "v");
    }

    public static String playlistIdFromUrl(String normalizedPlaylistUrl) {
        return uniqueQueryValue(URI.create(normalizedPlaylistUrl).getRawQuery(), "list");
    }

    private static URI validatedUri(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("La URL es obligatoria.");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("La URL no tiene un formato válido.", e);
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
            || !isYoutubeHost(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Solo se aceptan URLs HTTPS de YouTube o youtu.be.");
        }
        return uri;
    }

    private static String uniqueQueryValue(String rawQuery, String name) {
        String value = null;
        if (rawQuery == null) {
            return null;
        }
        for (String parameter : rawQuery.split("&")) {
            String[] pair = parameter.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name)) {
                if (value != null) {
                    throw new IllegalArgumentException("La URL contiene más de un parámetro '" + name + "'.");
                }
                value = URLDecoder.decode(pair.length == 1 ? "" : pair[1], StandardCharsets.UTF_8);
            }
        }
        return value;
    }

    private static String removeQueryParameter(URI uri, String parameterToRemove) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null) {
            return uri.toString();
        }
        List<String> retained = Arrays.stream(rawQuery.split("&"))
            .filter(parameter -> {
                String[] pair = parameter.split("=", 2);
                return !URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(parameterToRemove);
            })
            .toList();
        StringBuilder normalized = new StringBuilder()
            .append(uri.getScheme()).append("://").append(uri.getRawAuthority()).append(uri.getRawPath());
        if (!retained.isEmpty()) {
            normalized.append('?').append(String.join("&", retained));
        }
        if (uri.getRawFragment() != null) {
            normalized.append('#').append(uri.getRawFragment());
        }
        return normalized.toString();
    }

    private static boolean isShortYoutubeHost(String host) {
        return "youtu.be".equalsIgnoreCase(host);
    }

    private static boolean isYoutubeHost(String host) {
        return host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtu.be");
    }
}
