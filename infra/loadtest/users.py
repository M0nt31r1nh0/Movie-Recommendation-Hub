#!/usr/bin/env python3
"""Creates, or deletes, the load test's users and its Keycloak client, through Keycloak's admin API.

  python3 users.py setup    --url https://<ip> --users 200     (just setup)
  python3 users.py teardown --url https://<ip>                 (just teardown)

setup adds, to the movies realm:
  - movies-loadtest, a public client with direct access grants (username and password), so k6 can log in
    without a browser. Its tokens carry the same three audiences as the frontend's, so the APIs accept them.
    The realm's own clients don't allow this grant; teardown deletes this one again.
  - loadtest-001 ... loadtest-<N>, all with one generated password, written to .secrets/password.
Running it again only adds what is missing. Standard library only: it runs in the management container.
"""
import argparse
import json
import secrets
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
PASSWORD_FILE = HERE / ".secrets" / "password"
CLIENT_ID = "movies-loadtest"
AUDIENCES = ["movies-service", "profiles-service", "recommendation-service"]


class Keycloak:
    def __init__(self, url, admin_user, admin_password, realm):
        self.url = url.rstrip("/")
        self.realm = realm
        self.admin_user = admin_user
        self.admin_password = admin_password
        self.ctx = ssl._create_unverified_context()  # the deployment's certificate is self-signed
        self.token, self.token_until = None, 0

    def _token(self):
        # The master realm's admin tokens live one minute: get a new one every 30 s.
        if time.time() > self.token_until:
            form = urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli",
                                           "username": self.admin_user, "password": self.admin_password})
            req = urllib.request.Request(f"{self.url}/realms/master/protocol/openid-connect/token",
                                         data=form.encode(), method="POST")
            with urllib.request.urlopen(req, context=self.ctx) as res:
                self.token = json.load(res)["access_token"]
            self.token_until = time.time() + 30
        return self.token

    def call(self, method, path, body=None):
        """Returns (status, parsed body or None)."""
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(f"{self.url}/admin/realms/{self.realm}{path}", data=data, method=method,
                                     headers={"Authorization": f"Bearer {self._token()}",
                                              "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, context=self.ctx) as res:
                raw = res.read()
                return res.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(errors="replace")


def user_name(prefix, i):
    return f"{prefix}{i:03d}"


def setup(kc, args):
    PASSWORD_FILE.parent.mkdir(exist_ok=True)
    if PASSWORD_FILE.exists():
        password = PASSWORD_FILE.read_text().strip()
    else:
        password = "Lt-" + secrets.token_urlsafe(18)
        PASSWORD_FILE.write_text(password + "\n")

    client = {
        "clientId": CLIENT_ID, "name": "Load test (temporary: delete with users.py teardown)",
        "protocol": "openid-connect", "publicClient": True, "directAccessGrantsEnabled": True,
        "standardFlowEnabled": False, "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
        "protocolMappers": [{
            "name": f"{a} audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
            "config": {"included.custom.audience": a, "access.token.claim": "true",
                       "id.token.claim": "false", "introspection.token.claim": "true"},
        } for a in AUDIENCES],
    }
    status, body = kc.call("POST", "/clients", client)
    if status == 201:
        print(f"client {CLIENT_ID}: created")
    elif status == 409:
        print(f"client {CLIENT_ID}: already there")
    else:
        sys.exit(f"Couldn't create the client: {status} {body}")

    created = existing = 0
    for i in range(1, args.users + 1):
        name = user_name(args.prefix, i)
        status, body = kc.call("POST", "/users", {
            "username": name, "email": f"{name}@loadtest.invalid", "firstName": "Load", "lastName": f"Test {i:03d}",
            "enabled": True, "emailVerified": True, "requiredActions": [],
            "credentials": [{"type": "password", "value": password, "temporary": False}],
        })
        if status == 201:
            created += 1
        elif status == 409:
            existing += 1
        else:
            sys.exit(f"Couldn't create {name}: {status} {body}")
    print(f"users {user_name(args.prefix, 1)}..{user_name(args.prefix, args.users)}: "
          f"{created} created, {existing} already there; password in {PASSWORD_FILE.relative_to(HERE)}")


def teardown(kc, args):
    deleted = 0
    while True:
        status, users = kc.call("GET", f"/users?search={urllib.parse.quote(args.prefix)}&first=0&max=500&briefRepresentation=true")
        if status != 200:
            sys.exit(f"Couldn't list the users: {status} {users}")
        ours = [u for u in users if u["username"].startswith(args.prefix)]
        if not ours:
            break
        for u in ours:
            status, body = kc.call("DELETE", f"/users/{u['id']}")
            if status not in (204, 404):
                sys.exit(f"Couldn't delete {u['username']}: {status} {body}")
            deleted += 1
    print(f"users {args.prefix}*: {deleted} deleted")

    status, clients = kc.call("GET", f"/clients?clientId={CLIENT_ID}")
    if status != 200:
        sys.exit(f"Couldn't look up the client: {status} {clients}")
    for c in clients:
        kc.call("DELETE", f"/clients/{c['id']}")
    print(f"client {CLIENT_ID}: {'deleted' if clients else 'not there'}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("action", choices=["setup", "teardown"])
    p.add_argument("--url", required=True, help="the application, e.g. https://34.90.1.2")
    p.add_argument("--keycloak-url", help="Keycloak's base address (default: <url>/auth; locally http://localhost:8180)")
    p.add_argument("--users", type=int, default=200)
    p.add_argument("--prefix", default="loadtest-")
    p.add_argument("--realm", default="movies")
    p.add_argument("--admin-user", default="admin")
    p.add_argument("--admin-password-file", default=str(HERE.parent / "ansible" / ".secrets" / "keycloak-admin"),
                   help="default: infra/ansible/.secrets/keycloak-admin (written by the playbooks)")
    p.add_argument("--admin-password", help="instead of the file, e.g. admin for the local setup")
    args = p.parse_args()

    password = args.admin_password or Path(args.admin_password_file).read_text().strip()
    kc = Keycloak(args.keycloak_url or f"{args.url.rstrip('/')}/auth", args.admin_user, password, args.realm)
    (setup if args.action == "setup" else teardown)(kc, args)


if __name__ == "__main__":
    main()
