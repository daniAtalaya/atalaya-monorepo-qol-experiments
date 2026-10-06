package com.atalaya.toolbox.gateway;

import com.atalaya.toolbox.gateway.configuration.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

@RestController
public class GatewayProxyController {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    private final GatewayProperties properties;
    private final RestClient restClient;

    public GatewayProxyController(GatewayProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    /** Large archives and multipart uploads must pass through without byte-array buffering. */
    @RequestMapping({"/api/preferences/backups", "/api/preferences/backups/**"})
    public void proxyBackups(HttpServletRequest incoming, HttpServletResponse outgoing) throws IOException {
        GatewayProperties.Service service = properties.getServices().get("preferences");
        if (service == null || service.baseUrl() == null || service.baseUrl().isBlank()) {
            throw new UnknownGatewayServiceException("preferences");
        }
        String target = trimTrailingSlash(service.baseUrl())
            + incoming.getRequestURI().substring(incoming.getContextPath().length());
        if (incoming.getQueryString() != null) target += "?" + incoming.getQueryString();
        RestClient.RequestBodySpec request = restClient.method(HttpMethod.valueOf(incoming.getMethod()))
            .uri(URI.create(target)).headers(headers -> copyRequestHeaders(incoming, headers));
        if (incoming.getContentLengthLong() > 0 || incoming.getHeader("Transfer-Encoding") != null) {
            request.body(output -> StreamUtils.copy(incoming.getInputStream(), output));
        }
        request.exchange((clientRequest, response) -> {
            outgoing.setStatus(response.getStatusCode().value());
            response.getHeaders().forEach((name, values) -> {
                if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    values.forEach(value -> outgoing.addHeader(name, value));
                }
            });
            try {
                StreamUtils.copy(response.getBody(), outgoing.getOutputStream());
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return null;
        });
    }

    @RequestMapping("/api/{service}/**")
    public ResponseEntity<byte[]> proxy(
            @PathVariable String service,
            HttpServletRequest incomingRequest) throws IOException {
        GatewayProperties.Service targetService = properties.getServices().get(service);
        if (targetService == null || targetService.baseUrl() == null || targetService.baseUrl().isBlank()) {
            throw new UnknownGatewayServiceException(service);
        }
        String path = incomingRequest.getRequestURI().substring(incomingRequest.getContextPath().length());
        String target = trimTrailingSlash(targetService.baseUrl()) + path;
        if (incomingRequest.getQueryString() != null) {
            target += "?" + incomingRequest.getQueryString();
        }
        byte[] requestBody = StreamUtils.copyToByteArray(incomingRequest.getInputStream());
        HttpMethod method = HttpMethod.valueOf(incomingRequest.getMethod());
        RestClient.RequestBodySpec request = restClient.method(method)
                .uri(URI.create(target))
                .headers(headers -> copyRequestHeaders(incomingRequest, headers));
        if (requestBody.length > 0) {
            request.body(requestBody);
        }
        return request.exchange((clientRequest, response) -> {
            HttpHeaders responseHeaders = new HttpHeaders();
            response.getHeaders().forEach((name, values) -> {
                if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    responseHeaders.put(name, values);
                }
            });
            try {
                return ResponseEntity.status(response.getStatusCode())
                        .headers(responseHeaders)
                        .body(StreamUtils.copyToByteArray(response.getBody()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpHeaders destination) {
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                destination.add(name, values.nextElement());
            }
        }
    }

    private String trimTrailingSlash(String value) {
        return value.replaceFirst("/+$", "");
    }
}
