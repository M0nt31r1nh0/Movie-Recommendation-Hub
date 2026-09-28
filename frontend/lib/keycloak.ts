import type Keycloak from "keycloak-js";

const config = {
  url: process.env.NEXT_PUBLIC_KEYCLOAK_URL ?? "http://localhost:8180",
  realm: process.env.NEXT_PUBLIC_KEYCLOAK_REALM ?? "movies",
  clientId: process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID ?? "movies-frontend",
};

let keycloak: Promise<Keycloak> | undefined;

/**
 * The page's Keycloak client. A Keycloak instance can only be initialised once per page load, so every
 * caller shares this one; the first caller's options win. With `loginRequired`, a user who isn't logged
 * in is sent to Keycloak's login page and comes back to the current URL.
 *
 * Browser only: call it from effects or event handlers, never while rendering on the server.
 */
export function getKeycloak({ loginRequired = false } = {}): Promise<Keycloak> {
  keycloak ??= import("keycloak-js").then(async ({ default: Keycloak }) => {
    const kc = new Keycloak(config);
    await kc.init({
      onLoad: loginRequired ? "login-required" : undefined,
      pkceMethod: "S256",
      checkLoginIframe: false,
    });
    return kc;
  });
  return keycloak;
}
