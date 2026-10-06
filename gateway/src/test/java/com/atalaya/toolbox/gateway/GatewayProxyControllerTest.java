package com.atalaya.toolbox.gateway;

import com.atalaya.toolbox.gateway.configuration.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class GatewayProxyControllerTest {

    @Test
    void streamsBackupUploadsAndDownloadsAndPreservesAttachmentHeaders() throws Exception {
        RestClient.Builder clientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
        byte[] body = new byte[] {1, 2, 3, 0, -1};
        server.expect(requestTo("http://preferences:8083/api/preferences/backups/restore/preview"))
            .andExpect(content().bytes(body))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"fileCount\":3}"));
        server.expect(requestTo("http://preferences:8083/api/preferences/backups/archive-id"))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition", "attachment; filename=backup.zip").body(body));
        GatewayProperties properties = new GatewayProperties();
        properties.setServices(Map.of("preferences", new GatewayProperties.Service("http://preferences:8083", "/v3/api-docs")));
        var controller = new GatewayProxyController(properties, clientBuilder);
        var upload = new MockHttpServletRequest("POST", "/api/preferences/backups/restore/preview");
        upload.setContentType("multipart/form-data; boundary=example");
        upload.setContent(body);
        var uploadResponse = new MockHttpServletResponse();
        controller.proxyBackups(upload, uploadResponse);
        assertEquals("{\"fileCount\":3}", uploadResponse.getContentAsString());
        var download = new MockHttpServletRequest("GET", "/api/preferences/backups/archive-id");
        var downloadResponse = new MockHttpServletResponse();
        controller.proxyBackups(download, downloadResponse);
        org.junit.jupiter.api.Assertions.assertArrayEquals(body, downloadResponse.getContentAsByteArray());
        assertEquals("attachment; filename=backup.zip", downloadResponse.getHeader("Content-Disposition"));
        server.verify();
    }

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
