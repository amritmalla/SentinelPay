import { FormEvent, useState } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

export function LoginPage() {
  const { token, login } = useAuth();
  const [role, setRole] = useState<"MERCHANT" | "OPS">("MERCHANT");
  const [merchantId, setMerchantId] = useState("6f3a1c2e-0b7d-4e9a-9c11-2a4b6d8e0f12");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  if (token) {
    return <Navigate to="/payments" replace />;
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const response = await fetch(`${API_BASE}/dev/token`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ role, merchant_id: merchantId }),
      });
      if (!response.ok) {
        throw new Error(`Token mint failed (${response.status})`);
      }
      const body = (await response.json()) as { token: string };
      login(body.token);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Login failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="panel login-panel">
      <h1>Sign in (dev)</h1>
      <p className="muted">
        Tokens are kept in memory only — refreshing the page clears your session.
      </p>
      <form onSubmit={onSubmit}>
        <label>
          Role
          <select value={role} onChange={(e) => setRole(e.target.value as "MERCHANT" | "OPS")}>
            <option value="MERCHANT">MERCHANT</option>
            <option value="OPS">OPS</option>
          </select>
        </label>
        <label>
          Merchant ID
          <input value={merchantId} onChange={(e) => setMerchantId(e.target.value)} />
        </label>
        {error && <p className="error">{error}</p>}
        <button type="submit" disabled={loading}>
          {loading ? "Minting token…" : "Continue"}
        </button>
      </form>
    </section>
  );
}
