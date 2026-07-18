import { Link, Outlet } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export function Layout() {
  const { role, logout } = useAuth();

  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <strong>SentinelPay</strong>
          <span className="muted"> Dashboard</span>
        </div>
        <nav>
          <Link to="/payments">Payments</Link>
          {role === "OPS" && <Link to="/ops/routing">Routing</Link>}
          <button type="button" onClick={logout}>
            Log out
          </button>
        </nav>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  );
}
