import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { getPaymentSummary, getPaymentTrail } from "../api/client";
import type { DecisionTrail, PaymentSummary } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { Badge } from "../components/Badge";

export function PaymentDetailPage() {
  const { paymentId = "" } = useParams();
  const { token, role } = useAuth();
  const [summary, setSummary] = useState<PaymentSummary | null>(null);
  const [trail, setTrail] = useState<DecisionTrail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token || !paymentId) {
      return;
    }
    getPaymentSummary(token, paymentId)
      .then(setSummary)
      .catch((err: Error) => setError(err.message));

    if (role === "OPS") {
      getPaymentTrail(token, paymentId)
        .then(setTrail)
        .catch((err: Error) => setError(err.message));
    }
  }, [token, paymentId, role]);

  return (
    <section className="panel">
      <h1>Payment detail</h1>
      <p className="muted">{paymentId}</p>
      {error && <p className="error">{error}</p>}

      {summary && (
        <div className="detail-grid">
          <article>
            <h2>Merchant summary</h2>
            <dl>
              <dt>Status</dt>
              <dd>
                <Badge label={summary.status} />
              </dd>
              <dt>Risk band</dt>
              <dd>{summary.risk?.scoreBand ?? "—"}</dd>
              <dt>Recommendation</dt>
              <dd>{summary.risk?.recommendation ?? "—"}</dd>
              <dt>Provider attempts</dt>
              <dd>{summary.outcome.providerCount}</dd>
              <dt>Recovered</dt>
              <dd>{summary.outcome.recovered ? "Yes" : "No"}</dd>
              <dt>Final provider</dt>
              <dd>{summary.outcome.finalProviderSlot ?? "—"}</dd>
            </dl>
          </article>

          {role === "OPS" && trail && (
            <article>
              <h2>Decision trail (OPS)</h2>
              {trail.risk && (
                <>
                  <p>
                    Score {trail.risk.score.toFixed(3)} · {trail.risk.recommendation} ·{" "}
                    {trail.risk.modelVersion}
                  </p>
                  <ul>
                    {trail.risk.contributingFactors.map((factor) => (
                      <li key={factor}>{factor}</li>
                    ))}
                  </ul>
                </>
              )}
              {trail.routing && (
                <pre>{JSON.stringify(trail.routing, null, 2)}</pre>
              )}
              <h3>Attempts</h3>
              <ul>
                {trail.attempts.map((attempt) => (
                  <li key={attempt.attemptNumber}>
                    #{attempt.attemptNumber} {attempt.provider} → {attempt.outcome}
                  </li>
                ))}
              </ul>
            </article>
          )}
        </div>
      )}
    </section>
  );
}
