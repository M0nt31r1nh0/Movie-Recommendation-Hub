"use client";

import Link from "next/link";
import type Keycloak from "keycloak-js";
import { createContext, use, useEffect, useState } from "react";
import { getKeycloak } from "@/lib/keycloak";

const KeycloakContext = createContext<Keycloak | null>(null);

/** The logged-in user's Keycloak client. Only usable below <AuthGate>, which guarantees a login. */
export function useKeycloak(): Keycloak {
  const keycloak = use(KeycloakContext);
  if (!keycloak) throw new Error("useKeycloak() must be used inside <AuthGate>");
  return keycloak;
}

/**
 * Makes sure the user is logged in (sending them to Keycloak's login page if not), then shows the
 * top bar and the page. Everything under /movies renders inside it.
 */
export default function AuthGate({ children }: { children: React.ReactNode }) {
  const [keycloak, setKeycloak] = useState<Keycloak | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getKeycloak({ loginRequired: true })
      .then(setKeycloak)
      .catch(() => setError("Couldn't reach the login server. Is Keycloak running?"));
  }, []);

  return (
    <KeycloakContext value={keycloak}>
      <header className="flex h-16 shrink-0 items-center justify-between border-b border-foreground/10 px-6">
        <Link href="/movies" className="text-lg font-semibold">
          Movies
        </Link>
        {keycloak && (
          <div className="flex items-center gap-3 text-sm">
            <span className="text-foreground/70">{keycloak.tokenParsed?.preferred_username}</span>
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
        children
      ) : (
        <main className="flex-1 px-6 py-8">
          <p className={error ? "text-red-600" : "text-foreground/70"}>{error ?? "Logging in…"}</p>
        </main>
      )}
    </KeycloakContext>
  );
}
