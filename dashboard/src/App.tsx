import { Navigate, Route, Routes } from "react-router-dom";
import { useAuth } from "./auth/AuthContext";
import { Layout } from "./components/Layout";
import { LoginPage } from "./routes/LoginPage";
import { OpsRoutingPage } from "./routes/OpsRoutingPage";
import { PaymentDetailPage } from "./routes/PaymentDetailPage";
import { PaymentsPage } from "./routes/PaymentsPage";

function Protected({ children }: { children: React.ReactNode }) {
  const { token } = useAuth();
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return children;
}

function OpsOnly({ children }: { children: React.ReactNode }) {
  const { role } = useAuth();
  if (role !== "OPS") {
    return <Navigate to="/payments" replace />;
  }
  return children;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <Protected>
            <Layout />
          </Protected>
        }
      >
        <Route index element={<Navigate to="/payments" replace />} />
        <Route path="/payments" element={<PaymentsPage />} />
        <Route path="/payments/:paymentId" element={<PaymentDetailPage />} />
        <Route
          path="/ops/routing"
          element={
            <OpsOnly>
              <OpsRoutingPage />
            </OpsOnly>
          }
        />
      </Route>
    </Routes>
  );
}
