"use client";

import { getKeycloak } from "@/lib/keycloak";

// Both go to Keycloak's pages, which then send the user on to the movies page.
async function goToKeycloak(page: "login" | "register") {
  const keycloak = await getKeycloak();
  const redirectUri = `${window.location.origin}/movies`;
  await (page === "login" ? keycloak.login({ redirectUri }) : keycloak.register({ redirectUri }));
}

export default function AuthButtons() {
  return (
    <div className="flex gap-2">
      <button
        type="button"
        onClick={() => goToKeycloak("login")}
        className="rounded-md px-4 py-2 text-sm font-medium hover:bg-foreground/10"
      >
        Log in
      </button>
      <button
        type="button"
        onClick={() => goToKeycloak("register")}
        className="rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background hover:opacity-85"
      >
        Register
      </button>
    </div>
  );
}
