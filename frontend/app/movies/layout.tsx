import AuthGate from "./auth-gate";

export default function MoviesLayout({ children }: LayoutProps<"/movies">) {
  return <AuthGate>{children}</AuthGate>;
}
