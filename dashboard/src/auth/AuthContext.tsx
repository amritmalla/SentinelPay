import { createContext, useContext, useMemo, useState, type ReactNode } from "react";
import type { Role } from "../api/types";

interface AuthState {
  token: string | null;
  role: Role | null;
  merchantId: string | null;
}

interface AuthContextValue extends AuthState {
  login: (token: string) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function parseJwt(token: string): { role: Role; merchantId: string } {
  const payload = token.split(".")[1];
  if (!payload) {
    throw new Error("Invalid JWT");
  }
  const decoded = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/"))) as {
    role?: string;
    sub?: string;
  };
  const role = decoded.role === "OPS" ? "OPS" : "MERCHANT";
  return { role, merchantId: decoded.sub ?? "" };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({
    token: null,
    role: null,
    merchantId: null,
  });

  const value = useMemo<AuthContextValue>(
    () => ({
      ...state,
      login: (token: string) => {
        const { role, merchantId } = parseJwt(token);
        setState({ token, role, merchantId });
      },
      logout: () => setState({ token: null, role: null, merchantId: null }),
    }),
    [state],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return ctx;
}
