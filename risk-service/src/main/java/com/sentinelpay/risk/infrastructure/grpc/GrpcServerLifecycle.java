package com.sentinelpay.risk.infrastructure.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private final int port;
    private Server server;

    public GrpcServerLifecycle(@Value("${sentinelpay.risk.grpc.port}") int port) {
        this.port = port;
        this.server = ServerBuilder.forPort(port)
                .addService(new RiskScoringServer())
                .build();
    }

    @Override
    public void start() {
        try {
            server.start();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to start gRPC server on port " + port, ex);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
