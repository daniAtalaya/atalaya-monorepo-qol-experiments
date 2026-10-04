package com.atalaya.toolbox.gateway;

import com.atalaya.toolbox.gateway.configuration.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class GatewayProxyControllerTest {

    @Test
    void forwardsConfiguredServiceRequestsIncludingQueryAndResponseStatus() throws Exception {
        RestClient.Builder clientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
        server.expect(requestTo("http://youtube:8081/api/youtube/history?dateOrder=asc"))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("[]"));
        GatewayProperties properties = new GatewayProperties();
        properties.setServices(Map.of(
                "youtube", new GatewayProperties.Service("http://youtube:8081", "/v3/api-docs")));
        GatewayProxyController controller = new GatewayProxyController(properties, clientBuilder);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/youtube/history");
        request.setQueryString("dateOrder=asc");

        var response = controller.proxy("youtube", request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("[]", new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8));
        server.verify();
    }
}
