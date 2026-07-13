package com.sentinelpay.risk.infrastructure.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private final int port;
    private final RiskScoringServer riskScoringServer;
    private final GrpcTelemetry grpcTelemetry;
    private final RiskGrpcSpanAttributesInterceptor spanAttributesInterceptor;
    private Server server;

    public GrpcServerLifecycle(
            @Value("${sentinelpay.risk.grpc.port}") int port,
            RiskScoringServer riskScoringServer,
            GrpcTelemetry grpcTelemetry,
            RiskGrpcSpanAttributesInterceptor spanAttributesInterceptor) {
        this.port = port;
        this.riskScoringServer = riskScoringServer;
        this.grpcTelemetry = grpcTelemetry;
        this.spanAttributesInterceptor = spanAttributesInterceptor;
    }

    public int boundPort() {
        return server != null ? server.getPort() : port;
    }

    @Override
    public void start() {
        try {
            // gRPC applies interceptors in reverse registration order, so the LAST .intercept() is
            // outermost. Register grpcTelemetry last so it establishes the server span first;
            // spanAttributesInterceptor then runs inside that scope, where Span.current() is the
            // real span and the paymentId attribute attaches.
            server = ServerBuilder.forPort(port)
                    .addService(riskScoringServer)
                    .intercept(spanAttributesInterceptor)
                    .intercept(grpcTelemetry.newServerInterceptor())
                    .build()
                    .start();
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
