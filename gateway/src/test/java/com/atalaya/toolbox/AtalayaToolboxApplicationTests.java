package com.atalaya.toolbox;

import com.atalaya.toolbox.gateway.configuration.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class AtalayaToolboxApplicationTests {

    @Autowired
    private GatewayProperties gatewayProperties;

    @Test
    void contextLoads() {
        GatewayProperties.Service youtubeService = gatewayProperties.getServices().get("youtube");
        assertNotNull(youtubeService);
        assertFalse(youtubeService.baseUrl().isBlank());
    }

}
