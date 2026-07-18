import type {
  DecisionTrail,
  OpsRoutingConfig,
  OpsRoutingProviders,
  Payment,
  PaymentPage,
  PaymentSummary,
} from "./types";

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

function snakeToCamel(key: string): string {
  return key.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase());
}

function mapKeys<T>(value: unknown): T {
  if (Array.isArray(value)) {
    return value.map((item) => mapKeys(item)) as T;
  }
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [key, nested] of Object.entries(value as Record<string, unknown>)) {
      out[snakeToCamel(key)] = mapKeys(nested);
    }
    return out as T;
  }
  return value as T;
}

async function request<T>(path: string, token: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${token}`,
      ...(init?.headers ?? {}),
    },
  });
  if (!response.ok) {
    throw new Error(`API ${response.status}: ${path}`);
  }
  const json: unknown = await response.json();
  return mapKeys<T>(json);
}

export function listPayments(token: string, cursor?: string): Promise<PaymentPage> {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : "";
  return request<PaymentPage>(`/api/v1/payments${query}`, token);
}

export function getPayment(token: string, paymentId: string): Promise<Payment> {
  return request<Payment>(`/api/v1/payments/${paymentId}`, token);
}

export function getPaymentSummary(token: string, paymentId: string): Promise<PaymentSummary> {
  return request<PaymentSummary>(`/api/v1/payments/${paymentId}/summary`, token);
}

export function getPaymentTrail(token: string, paymentId: string): Promise<DecisionTrail> {
  return request<DecisionTrail>(`/api/v1/payments/${paymentId}/trail`, token);
}

export function getOpsRoutingProviders(token: string): Promise<OpsRoutingProviders> {
  return request<OpsRoutingProviders>("/api/v1/ops/routing/providers", token);
}

export function getOpsRoutingConfig(token: string): Promise<OpsRoutingConfig> {
  return request<OpsRoutingConfig>("/api/v1/ops/routing/config", token);
}

/** Merchant dashboard must use summary, never the OPS-only trail endpoint. */
export const MERCHANT_PAYMENT_DETAIL_PATH = "summary" as const;
