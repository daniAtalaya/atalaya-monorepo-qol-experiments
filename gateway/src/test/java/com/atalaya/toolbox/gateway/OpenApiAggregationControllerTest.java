package com.atalaya.toolbox.gateway;

import com.atalaya.toolbox.gateway.configuration.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenApiAggregationControllerTest {

    @Test
    void mergesConfiguredServiceOperationsAndNamespacesReferences() throws Exception {
        String serviceDocument = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "YouTube", "version": "1"},
                  "security": [{"BearerAuth": []}],
                  "paths": {
                    "/api/youtube/download": {
                      "post": {
                        "tags": ["youtube-download"],
                        "requestBody": {
                          "content": {
                            "application/json": {
                              "schema": {"$ref": "#/components/schemas/DownloadRequest"}
                            }
                          }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {"DownloadRequest": {"type": "object"}},
                    "securitySchemes": {"BearerAuth": {"type": "http", "scheme": "bearer"}}
                  },
                  "tags": [{"name": "youtube-download"}]
                }
                """;
        RestClient.Builder clientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
        server.expect(requestTo("http://youtube:8081/v3/api-docs"))
                .andRespond(withSuccess(serviceDocument, MediaType.APPLICATION_JSON));
        GatewayProperties properties = new GatewayProperties();
        properties.setServices(Map.of(
                "youtube", new GatewayProperties.Service("http://youtube:8081", "/v3/api-docs")));
        OpenApiAggregationController controller =
                new OpenApiAggregationController(properties, clientBuilder, new ObjectMapper());

        var document = new ObjectMapper().readTree(controller.aggregateOpenApiDocuments().getBody());

        assertEquals(
                "#/components/schemas/youtube__DownloadRequest",
                document.at("/paths/~1api~1youtube~1download/post/requestBody/content/application~1json/schema/$ref")
                        .asText());
        assertEquals("youtube - youtube-download", document.at("/tags/0/name").asText());
        assertEquals("youtube__BearerAuth", document.at("/paths/~1api~1youtube~1download/post/security/0")
                .fieldNames().next());
        assertTrue(document.path("components").path("securitySchemes").has("youtube__BearerAuth"));
        server.verify();
    }
}
