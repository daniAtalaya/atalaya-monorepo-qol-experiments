package com.atalaya.toolbox.gateway.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "toolbox.gateway")
public class GatewayProperties {

    private Map<String, Service> services = new LinkedHashMap<>();

    public Map<String, Service> getServices() {
        return services;
    }

    public void setServices(Map<String, Service> services) {
        this.services = services;
    }

    public record Service(String baseUrl, String openapiPath) {

        public String resolvedOpenApiPath() {
            return openapiPath == null || openapiPath.isBlank() ? "/v3/api-docs" : openapiPath;
        }
    }
}
