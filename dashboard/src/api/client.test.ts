import { describe, expect, it, vi } from "vitest";
import { getPaymentSummary, MERCHANT_PAYMENT_DETAIL_PATH } from "./client";

describe("api client", () => {
  it("merchant detail path uses summary, not trail", () => {
    expect(MERCHANT_PAYMENT_DETAIL_PATH).toBe("summary");
  });

  it("getPaymentSummary calls the summary endpoint", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        payment_id: "abc",
        status: "COMPLETED",
        amount_cents: 100,
        currency: "USD",
        created_at: "2026-01-01T00:00:00Z",
        outcome: { provider_count: 1, recovered: false, final_provider_slot: "mockpay" },
      }),
    });
    vi.stubGlobal("fetch", fetchMock);

    await getPaymentSummary("token", "abc");

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining("/api/v1/payments/abc/summary"),
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: "Bearer token" }) }),
    );
    expect(fetchMock.mock.calls[0]?.[0]).not.toContain("/trail");
  });
});
