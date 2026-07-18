import { useEffect, useState } from "react";
import { getOpsRoutingConfig, getOpsRoutingProviders } from "../api/client";
import type { OpsRoutingConfig, OpsRoutingProviders } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { Badge } from "../components/Badge";

export function OpsRoutingPage() {
  const { token } = useAuth();
  const [providers, setProviders] = useState<OpsRoutingProviders | null>(null);
  const [config, setConfig] = useState<OpsRoutingConfig | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) {
      return;
    }
    Promise.all([getOpsRoutingProviders(token), getOpsRoutingConfig(token)])
      .then(([providerPayload, configPayload]) => {
        setProviders(providerPayload);
        setConfig(configPayload);
      })
      .catch((err: Error) => setError(err.message));
  }, [token]);

  return (
    <section className="panel">
      <h1>Routing health</h1>
      {error && <p className="error">{error}</p>}
      {config && (
        <p>
          Policy <strong>{config.policy}</strong>
          {config.enabled ? "" : " (disabled)"}
        </p>
      )}
      {providers?.degraded && <Badge label="Degraded — Redis unavailable" tone="warning" />}
      <table>
        <thead>
          <tr>
            <th>Provider</th>
            <th>Breaker</th>
            <th>Success</th>
            <th>Latency EWMA</th>
            <th>Bandit θ</th>
            <th>Fee</th>
          </tr>
        </thead>
        <tbody>
          {providers?.providers.map((row) => (
            <tr key={row.provider}>
              <td>{row.provider}</td>
              <td>{row.breakerState}</td>
              <td>{(row.health.successRate * 100).toFixed(1)}%</td>
              <td>{row.health.latencyEwmaMs} ms</td>
              <td>{row.bandit.mean.toFixed(3)}</td>
              <td>
                {row.fee.feeBps} bps + {row.fee.feeFixedCents}¢
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
