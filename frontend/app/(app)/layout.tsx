import AuthGate from "./auth-gate";

/** Everything in (app) — /movies, /users — needs a login and shares the top bar. */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  return <AuthGate>{children}</AuthGate>;
}
