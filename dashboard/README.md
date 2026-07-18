# SentinelPay Dashboard

Role-switched React SPA for merchants (payment list + redacted summary) and operators
(full decision trail + live routing health).

## Dev

```bash
cd dashboard
npm install
npm run dev
```

Open http://localhost:5173 and sign in via the gateway's dev token endpoint (`POST /dev/token`).
Requires the stack running with the gateway on `dev` profile.

The JWT is stored **in memory only** (React context). Refreshing the page clears the session
by design — do not persist payment tokens in `localStorage`.

## API base URL

Set `VITE_API_BASE_URL` (default `http://localhost:8080`) when building or running Vite.
The compose service passes `http://localhost:8080` so browser calls hit the gateway directly;
CORS is enabled on the gateway `dev` profile for `http://localhost:5173`.

## Scripts

- `npm run lint` — ESLint
- `npm run typecheck` — TypeScript strict
- `npm test` — Vitest + React Testing Library
- `npm run build` — production bundle (no secrets embedded)
