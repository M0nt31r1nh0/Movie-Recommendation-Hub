"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type Keycloak from "keycloak-js";
import { createContext, use, useEffect, useState } from "react";
import { getProfile } from "@/lib/api";
import { recordVisit } from "@/lib/app-history";
import { getKeycloak } from "@/lib/keycloak";
import { startRecommendationEvents } from "@/lib/recommendation-events";
import RecommendationToasts from "./recommendation-toasts";

const KeycloakContext = createContext<Keycloak | null>(null);

/** The logged-in user's Keycloak client. Only usable below <AuthGate>, which guarantees a login. */
export function useKeycloak(): Keycloak {
  const keycloak = use(KeycloakContext);
  if (!keycloak) throw new Error("useKeycloak() must be used inside <AuthGate>");
  return keycloak;
}

/**
 * Makes sure the user is logged in (sending them to Keycloak's login page if not), then shows the
 * top bar and the page, and listens for the user's recommendation events while they're here.
 */
export default function AuthGate({ children }: { children: React.ReactNode }) {
  const [keycloak, setKeycloak] = useState<Keycloak | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getKeycloak({ loginRequired: true })
      .then((kc) => {
        setKeycloak(kc);
        // profiles-service creates a user's profile on their first request, so ask for it right away:
        // everyone who logs in shows up under Users. The movie pages work without it, so errors don't matter.
        getProfile(kc, "me").catch(() => undefined);
      })
      .catch(() => setError("Couldn't reach the login server. Is Keycloak running?"));
  }, []);

  useEffect(() => (keycloak ? startRecommendationEvents(keycloak) : undefined), [keycloak]);

  const pathname = usePathname();
  useEffect(() => recordVisit(pathname), [pathname]);

  return (
    <KeycloakContext value={keycloak}>
      <header className="flex h-16 shrink-0 items-center justify-between border-b border-foreground/10 px-6">
        <nav className="flex items-center gap-6">
          <Link href="/movies" className="text-lg font-semibold">
            Movies
          </Link>
          <NavLink href="/recommendations">Ask AI</NavLink>
          <NavLink href="/users">Users</NavLink>
        </nav>
        {keycloak && (
          <div className="flex items-center gap-3 text-sm">
            <NavLink href="/users/me">{keycloak.tokenParsed?.preferred_username}</NavLink>
            <button
              type="button"
              onClick={() => keycloak.logout({ redirectUri: window.location.origin })}
              className="rounded-md px-4 py-2 font-medium hover:bg-foreground/10"
            >
              Log out
            </button>
          </div>
        )}
      </header>
      {keycloak ? (
        <>
          {children}
          <RecommendationToasts />
        </>
      ) : (
        <main className="flex-1 px-6 py-8">
          <p className={error ? "text-red-600" : "text-foreground/70"}>{error ?? "Logging in…"}</p>
        </main>
      )}
    </KeycloakContext>
  );
}

function NavLink({ href, children }: { href: string; children: React.ReactNode }) {
  const pathname = usePathname();
  const active = pathname === href || (href === "/users" && pathname.startsWith("/users/") && pathname !== "/users/me");
  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      className={`text-sm font-medium hover:underline ${active ? "text-foreground" : "text-foreground/70"}`}
    >
      {children}
    </Link>
  );
}
