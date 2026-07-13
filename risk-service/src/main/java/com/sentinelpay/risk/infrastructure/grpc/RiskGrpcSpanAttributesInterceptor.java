package com.sentinelpay.risk.infrastructure.grpc;

import com.sentinelpay.proto.fraud.RiskScoreRequest;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.opentelemetry.api.trace.Span;
import org.springframework.stereotype.Component;

@Component
public class RiskGrpcSpanAttributesInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                if (message instanceof RiskScoreRequest request) {
                    Span.current().setAttribute("paymentId", request.getTransactionId());
                }
                super.onMessage(message);
            }
        };
    }
}
