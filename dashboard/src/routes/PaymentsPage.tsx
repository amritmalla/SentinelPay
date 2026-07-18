import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { listPayments } from "../api/client";
import type { Payment } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { Badge } from "../components/Badge";

function formatAmount(cents: number, currency: string): string {
  return new Intl.NumberFormat(undefined, { style: "currency", currency }).format(cents / 100);
}

export function PaymentsPage() {
  const { token, merchantId } = useAuth();
  const [payments, setPayments] = useState<Payment[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) {
      return;
    }
    listPayments(token)
      .then((page) => setPayments(page.data))
      .catch((err: Error) => setError(err.message));
  }, [token]);

  return (
    <section className="panel">
      <h1>Payments</h1>
      {error && <p className="error">{error}</p>}
      <table>
        <thead>
          <tr>
            <th>Payment</th>
            <th>Status</th>
            <th>Amount</th>
            <th>Provider</th>
          </tr>
        </thead>
        <tbody>
          {payments.map((payment) => (
            <tr key={payment.paymentId}>
              <td>
                <Link to={`/payments/${payment.paymentId}`}>{payment.paymentId.slice(0, 8)}…</Link>
              </td>
              <td>
                <Badge label={payment.status} tone={payment.status === "BLOCKED" ? "danger" : "neutral"} />
              </td>
              <td>{formatAmount(payment.amountCents, payment.currency)}</td>
              <td>{payment.provider ?? "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {payments.length === 0 && !error && (
        // Payments are merchant-scoped, and scripts/demo.sh mints a fresh random merchant each
        // run — so "empty" almost always means "right data, wrong merchant id", not "no data".
        // Say that explicitly instead of "run the demo", which is misleading once they have.
        <div className="muted empty-state">
          <p>
            No payments for merchant <code>{merchantId ?? "—"}</code>.
          </p>
          <p>
            Payments are scoped to a merchant id, and <code>scripts/demo.sh</code> uses a new random
            one on every run — so an empty list usually means you are signed in as a different
            merchant, not that there is no data.
          </p>
          <p>
            Run <code>scripts/demo.sh</code> and sign in with the merchant id it prints at the end,
            or pin one with <code>MERCHANT_ID=&lt;uuid&gt; scripts/demo.sh</code> and reuse it here.
          </p>
        </div>
      )}
    </section>
  );
}
