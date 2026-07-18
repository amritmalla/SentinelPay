import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { PaymentsPage } from "./PaymentsPage";

vi.mock("../auth/AuthContext", () => ({
  useAuth: () => ({
    token: "test-token",
    role: "MERCHANT",
    merchantId: "6f3a1c2e-0b7d-4e9a-9c11-2a4b6d8e0f12",
    login: vi.fn(),
    logout: vi.fn(),
  }),
}));

vi.mock("../api/client", () => ({
  listPayments: vi.fn().mockResolvedValue({
    data: [
      {
        paymentId: "6f3a1c2e-0b7d-4e9a-9c11-2a4b6d8e0f12",
        merchantId: "6f3a1c2e-0b7d-4e9a-9c11-2a4b6d8e0f12",
        status: "COMPLETED",
        amountCents: 4200,
        currency: "USD",
        provider: "mockpay",
        createdAt: "2026-01-01T00:00:00Z",
      },
    ],
    page: { nextCursor: null, limit: 20 },
  }),
}));

function renderPage() {
  return render(
    <MemoryRouter>
      <PaymentsPage />
    </MemoryRouter>,
  );
}

describe("PaymentsPage", () => {
  it("renders payment rows", async () => {
    renderPage();
    expect(await screen.findByText("COMPLETED")).toBeInTheDocument();
    expect(screen.getByRole("link")).toHaveAttribute(
      "href",
      "/payments/6f3a1c2e-0b7d-4e9a-9c11-2a4b6d8e0f12",
    );
  });
});
