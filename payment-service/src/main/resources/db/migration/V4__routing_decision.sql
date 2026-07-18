-- V4__routing_decision.sql — persist smart-routing rationale on the payment aggregate.
ALTER TABLE payments ADD COLUMN routing_decision jsonb;

COMMENT ON COLUMN payments.routing_decision IS
    'Explainable routing decision: ordered providers, per-provider rationale, policy flags.';
