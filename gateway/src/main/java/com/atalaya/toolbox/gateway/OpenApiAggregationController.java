package com.atalaya.toolbox.gateway;

import com.atalaya.toolbox.gateway.configuration.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@RestController
public class OpenApiAggregationController {

    private final GatewayProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OpenApiAggregationController(
            GatewayProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @GetMapping("/gateway/openapi.json")
    public ResponseEntity<String> aggregateOpenApiDocuments() {
        ObjectNode aggregate = objectMapper.createObjectNode();
        aggregate.put("openapi", "3.0.1");
        aggregate.set("info", objectMapper.createObjectNode()
                .put("title", "Atalaya Toolbox API")
                .put("version", "aggregated"));
        ObjectNode paths = aggregate.putObject("paths");
        ObjectNode components = aggregate.putObject("components");
        ArrayNode tags = aggregate.putArray("tags");
        ArrayNode services = aggregate.putArray("x-aggregated-services");

        properties.getServices().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String serviceName = entry.getKey();
                    GatewayProperties.Service service = entry.getValue();
                    JsonNode document = fetchDocument(service);
                    if (services.isEmpty()) {
                        aggregate.put("openapi", document.path("openapi").asText("3.0.1"));
                    }
                    services.add(serviceName);
                    mergePaths(paths, document.path("paths"), document.path("security"), serviceName);
                    mergeComponents(components, document.path("components"), serviceName);
                    mergeTags(tags, document.path("tags"), serviceName);
                });
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(aggregate));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("No se pudo generar el OpenAPI agregado.", e);
        }
    }

    private JsonNode fetchDocument(GatewayProperties.Service service) {
        if (service.baseUrl() == null || service.baseUrl().isBlank()) {
            throw new IllegalStateException("Falta configurar base-url para un servicio del gateway.");
        }
        String url = service.baseUrl().replaceFirst("/+$", "") + service.resolvedOpenApiPath();
        String content = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
        JsonNode document;
        try {
            document = content == null ? null : objectMapper.readTree(content);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("El servicio " + url + " devolvió JSON inválido.", e);
        }
        if (document == null || !document.path("openapi").isTextual()) {
            throw new IllegalStateException("El servicio " + url + " no devolvió un documento OpenAPI válido.");
        }
        return document;
    }

    private void mergePaths(
            ObjectNode aggregate,
            JsonNode servicePaths,
            JsonNode defaultSecurity,
            String serviceName) {
        if (!servicePaths.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> pathEntries = servicePaths.fields();
        while (pathEntries.hasNext()) {
            Map.Entry<String, JsonNode> entry = pathEntries.next();
            if (aggregate.has(entry.getKey())) {
                throw new IllegalStateException(
                        "Conflicto de ruta OpenAPI '" + entry.getKey() + "' entre servicios.");
            }
            JsonNode rewritten = rewriteReferences(entry.getValue(), serviceName);
            prefixOperationTags(rewritten, serviceName);
            applyDefaultSecurity(rewritten, defaultSecurity, serviceName);
            aggregate.set(entry.getKey(), rewritten);
        }
    }

    private void applyDefaultSecurity(JsonNode pathItem, JsonNode defaultSecurity, String serviceName) {
        if (!pathItem.isObject()) {
            return;
        }
        pathItem.fields().forEachRemaining(entry -> {
            JsonNode operation = entry.getValue();
            if (!operation.isObject()) {
                return;
            }
            ObjectNode operationObject = (ObjectNode) operation;
            if (!operationObject.has("security") && defaultSecurity.isArray()) {
                operationObject.set("security", namespaceSecurityRequirements(defaultSecurity, serviceName));
            }
        });
    }

    private void mergeComponents(ObjectNode aggregate, JsonNode serviceComponents, String serviceName) {
        if (!serviceComponents.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> sections = serviceComponents.fields();
        while (sections.hasNext()) {
            Map.Entry<String, JsonNode> section = sections.next();
            ObjectNode aggregateSection = aggregate.has(section.getKey())
                    ? (ObjectNode) aggregate.get(section.getKey())
                    : aggregate.putObject(section.getKey());
            if (!section.getValue().isObject()) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> definitions = section.getValue().fields();
            while (definitions.hasNext()) {
                Map.Entry<String, JsonNode> definition = definitions.next();
                String namespacedName = componentName(serviceName, definition.getKey());
                aggregateSection.set(
                        namespacedName, rewriteReferences(definition.getValue(), serviceName));
            }
        }
    }

    private void mergeTags(ArrayNode aggregate, JsonNode serviceTags, String serviceName) {
        if (!serviceTags.isArray()) {
            return;
        }
        for (JsonNode tag : serviceTags) {
            if (!tag.path("name").isTextual()) {
                continue;
            }
            ObjectNode namespacedTag = (ObjectNode) tag.deepCopy();
            namespacedTag.put("name", serviceName + " - " + tag.path("name").asText());
            aggregate.add(namespacedTag);
        }
    }

    private void prefixOperationTags(JsonNode pathItem, String serviceName) {
        if (!pathItem.isObject()) {
            return;
        }
        Iterator<JsonNode> operations = pathItem.elements();
        while (operations.hasNext()) {
            JsonNode operation = operations.next();
            if (operation.isObject() && operation.path("tags").isArray()) {
                ArrayNode operationTags = (ArrayNode) operation.path("tags");
                for (int index = 0; index < operationTags.size(); index++) {
                    operationTags.set(index, objectMapper.getNodeFactory()
                            .textNode(serviceName + " - " + operationTags.get(index).asText()));
                }
            }
        }
    }

    private JsonNode rewriteReferences(JsonNode source, String serviceName) {
        JsonNode copy = source.deepCopy();
        rewriteNode(copy, serviceName);
        return copy;
    }

    private void rewriteNode(JsonNode node, String serviceName) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (object.path("$ref").isTextual()) {
                object.put("$ref", namespaceReference(object.path("$ref").asText(), serviceName));
            }
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            fields.forEachRemaining(entries::add);
            List<JsonNode> children = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : entries) {
                if ("security".equals(field.getKey()) && field.getValue().isArray()) {
                    prefixSecurityRequirements(field.getValue(), serviceName);
                }
                children.add(field.getValue());
            }
            children.forEach(child -> rewriteNode(child, serviceName));
        } else if (node.isArray()) {
            node.forEach(child -> rewriteNode(child, serviceName));
        }
    }

    private String namespaceReference(String reference, String serviceName) {
        String prefix = "#/components/";
        if (!reference.startsWith(prefix)) {
            return reference;
        }
        String[] parts = reference.substring(prefix.length()).split("/", 2);
        if (parts.length != 2) {
            return reference;
        }
        return prefix + parts[0] + "/" + componentName(serviceName, parts[1]);
    }

    private String componentName(String serviceName, String name) {
        return serviceName + "__" + name;
    }

    private ArrayNode namespaceSecurityRequirements(JsonNode source, String serviceName) {
        ArrayNode requirements = (ArrayNode) source.deepCopy();
        prefixSecurityRequirements(requirements, serviceName);
        return requirements;
    }

    private void prefixSecurityRequirements(JsonNode requirements, String serviceName) {
        if (!requirements.isArray()) {
            return;
        }
        for (JsonNode requirement : requirements) {
            if (!requirement.isObject()) {
                continue;
            }
            ObjectNode namespaced = (ObjectNode) requirement;
            List<Map.Entry<String, JsonNode>> schemes = new ArrayList<>();
            namespaced.fields().forEachRemaining(schemes::add);
            for (Map.Entry<String, JsonNode> scheme : schemes) {
                namespaced.remove(scheme.getKey());
                namespaced.set(componentName(serviceName, scheme.getKey()), scheme.getValue());
            }
        }
    }
}
