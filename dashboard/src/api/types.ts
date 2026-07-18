export type Role = "MERCHANT" | "OPS";

export interface Payment {
  paymentId: string;
  merchantId: string;
  status: string;
  amountCents: number;
  currency: string;
  provider: string | null;
  createdAt: string;
}

export interface PaymentPage {
  data: Payment[];
  page: { nextCursor: string | null; limit: number };
}

export interface PaymentSummary {
  paymentId: string;
  status: string;
  amountCents: number;
  currency: string;
  createdAt: string;
  risk: { recommendation: string; scoreBand: string } | null;
  outcome: {
    providerCount: number;
    recovered: boolean;
    finalProviderSlot: string | null;
  };
}

export interface DecisionTrail {
  paymentId: string;
  status: string;
  risk: {
    score: number;
    recommendation: string;
    contributingFactors: string[];
    modelVersion: string;
  } | null;
  routing: Record<string, unknown> | null;
  attempts: Array<{
    attemptNumber: number;
    provider: string;
    outcome: string;
  }>;
}

export interface OpsRoutingProviders {
  degraded: boolean;
  providers: Array<{
    provider: string;
    enabled: boolean;
    breakerState: string;
    health: { successRate: number; latencyEwmaMs: number; degraded: boolean };
    bandit: { alpha: number; beta: number; mean: number; degraded: boolean };
    fee: { feeBps: number; feeFixedCents: number };
  }>;
}

export interface OpsRoutingConfig {
  enabled: boolean;
  policy: string;
  split: Record<string, number>;
}
