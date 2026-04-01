package org.example;

import org.example.voicingbackend.controller.AudioModelController;
import org.example.voicingbackend.config.ConfigurationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    
    public static void main(String[] args) {
        logger.info("Starting VoicingBackend gRPC server (Armeria)...");
        
        // Load configuration
        ConfigurationManager config = ConfigurationManager.getInstance();
        
        int port = config.getServerPort();
        String host = config.getServerHost();
        
        // Load CORS origins from configuration
        String[] origins = config.getCorsAllowedOrigins();

        // Build Armeria server with gRPC and gRPC-Web enabled + CORS
        com.linecorp.armeria.server.Server server = com.linecorp.armeria.server.Server.builder()
                .http(port)
                .accessLogWriter(com.linecorp.armeria.server.logging.AccessLogWriter.common(), true)
                .service(
                        com.linecorp.armeria.server.grpc.GrpcService.builder()
                                .addService((io.grpc.BindableService) new AudioModelController())
                                .supportedSerializationFormats(
                                        com.linecorp.armeria.common.grpc.GrpcSerializationFormats.values())
                                .build()
                )
                .decorator(
                        com.linecorp.armeria.server.cors.CorsService.builder(origins)
                                .allowRequestMethods(
                                        com.linecorp.armeria.common.HttpMethod.OPTIONS,
                                        com.linecorp.armeria.common.HttpMethod.POST)
                                .allowRequestHeaders(
                                       "content-type",
      "x-grpc-web",
      "grpc-timeout",
      "authorization",
      "x-user-agent",
      "connect-protocol-version",
      "connect-timeout-ms",
      "te",
      "accept",
      "grpc-accept-encoding")
                                .exposeHeaders("grpc-status", "grpc-message")
                                .allowCredentials()
                                .newDecorator()
                )
                .build();

        server.start().join();
        logger.info("Armeria gRPC server started on {}:{}", host, port);
        logger.info("gRPC-Web enabled; CORS configured for any origin");

        // Keep running
        try {
            server.blockUntilShutdown();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}