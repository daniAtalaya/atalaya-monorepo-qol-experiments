package com.atalaya.toolbox.gateway;

public class UnknownGatewayServiceException extends RuntimeException {

    public UnknownGatewayServiceException(String service) {
        super("No hay un servicio configurado en el gateway con el nombre '" + service + "'.");
    }
}
