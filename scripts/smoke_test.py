#!/usr/bin/env python3
"""
Smoke Test: F02 (Identity/RBAC) + F03 (Conversation Management)
Usage:  python scripts/smoke_test.py [--base http://localhost:8080]
Requires: app running on BASE, docker compose db up (for admin promotion)
"""

import argparse
import subprocess
import sys
import uuid

import requests

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
parser = argparse.ArgumentParser()
parser.add_argument("--base", default="http://localhost:8080")
ARGS = parser.parse_args()
BASE = ARGS.base.rstrip("/")

RUN = uuid.uuid4().hex[:8]
USER_EMAIL  = f"smoke_user_{RUN}@example.com"
ADMIN_EMAIL = f"smoke_admin_{RUN}@example.com"
NEW_EMAIL   = f"smoke_new_{RUN}@example.com"
PASSWORD    = "SmokeTest123!"
FAKE_ID     = "00000000-0000-0000-0000-000000000000"

passed = 0
failed = 0


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def check(label: str, actual: int, expected: int) -> None:
    global passed, failed
    if actual == expected:
        print(f"  PASS  {label} (HTTP {actual})")
        passed += 1
    else:
        print(f"  FAIL  {label} (expected {expected}, got {actual})")
        failed += 1


def api(method: str, path: str, *, token: str = None, json: dict = None) -> requests.Response:
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return requests.request(method, f"{BASE}{path}", headers=headers, json=json, timeout=10)


def promote_admin(email: str) -> None:
    subprocess.run(
        ["docker", "compose", "exec", "-T", "db",
         "psql", "-U", "aiassistant", "-d", "aiassistant",
         "-c", f"UPDATE users SET role='ROLE_ADMIN' WHERE email='{email}';"],
        check=True, capture_output=True,
    )


def cleanup(*emails: str) -> None:
    quoted = ",".join(f"'{e}'" for e in emails)
    subprocess.run(
        ["docker", "compose", "exec", "-T", "db",
         "psql", "-U", "aiassistant", "-d", "aiassistant",
         "-c", f"DELETE FROM users WHERE email IN ({quoted});"],
        check=False, capture_output=True,
    )


# ---------------------------------------------------------------------------
# Setup
# ---------------------------------------------------------------------------
print(f"\n=== Smoke Test (run={RUN}) ===")
print(f"\n[Setup] Creating test accounts...")

r = api("POST", "/api/auth/register", json={"email": USER_EMAIL, "password": PASSWORD})
if r.status_code != 201:
    print(f"  ERROR  user register failed ({r.status_code}): {r.text}")
    sys.exit(1)
user_token = r.json()["token"]

r = api("POST", "/api/auth/register", json={"email": ADMIN_EMAIL, "password": PASSWORD})
if r.status_code != 201:
    print(f"  ERROR  admin register failed ({r.status_code}): {r.text}")
    sys.exit(1)

try:
    promote_admin(ADMIN_EMAIL)
except subprocess.CalledProcessError as e:
    print(f"  WARN   could not promote admin via docker (skipping admin tests): {e}")
    admin_token = None
else:
    admin_token = api("POST", "/api/auth/login",
                      json={"email": ADMIN_EMAIL, "password": PASSWORD}).json().get("token")

print(f"  OK    user={USER_EMAIL}")
print(f"  OK    admin={ADMIN_EMAIL}")


# ---------------------------------------------------------------------------
# F02 — Identity / RBAC
# ---------------------------------------------------------------------------
print("\n[F02-1] Register validation")
check("new email → 201",           api("POST", "/api/auth/register", json={"email": NEW_EMAIL, "password": PASSWORD}).status_code, 201)
check("invalid email → 400",       api("POST", "/api/auth/register", json={"email": "not-an-email", "password": PASSWORD}).status_code, 400)
check("short password → 400",      api("POST", "/api/auth/register", json={"email": f"x_{RUN}@e.com", "password": "short"}).status_code, 400)

print("\n[F02-2] Duplicate email")
check("duplicate → 409",           api("POST", "/api/auth/register", json={"email": USER_EMAIL, "password": PASSWORD}).status_code, 409)

print("\n[F02-3] Login success")
login_r = api("POST", "/api/auth/login", json={"email": USER_EMAIL, "password": PASSWORD})
check("correct password → 200",    login_r.status_code, 200)

print("\n[F02-4] Login failure")
check("wrong password → 401",      api("POST", "/api/auth/login", json={"email": USER_EMAIL, "password": "wrong"}).status_code, 401)
check("unknown email → 401",       api("POST", "/api/auth/login", json={"email": f"nobody_{RUN}@e.com", "password": "x"}).status_code, 401)

print("\n[F02-5] JWT auth")
check("GET /users/me (valid) → 200",   api("GET", "/api/users/me", token=user_token).status_code, 200)
check("GET /users/me (no token) → 401", api("GET", "/api/users/me").status_code, 401)

print("\n[F02-6] RBAC")
check("user → GET /admin/users → 403", api("GET", "/api/admin/users", token=user_token).status_code, 403)
if admin_token:
    admin_list_r = api("GET", "/api/admin/users", token=admin_token)
    check("admin → GET /admin/users → 200", admin_list_r.status_code, 200)
    if "password" not in admin_list_r.text.lower():
        print("  PASS  admin list does not expose password hash")
        passed += 1
    else:
        print("  FAIL  admin list leaks password!")
        failed += 1
else:
    print("  SKIP  admin tests (no admin token)")

print("\n[F02-7] Health check")
check("GET /actuator/health → 200",    api("GET", "/actuator/health").status_code, 200)


# ---------------------------------------------------------------------------
# F03 — Conversation Management
# ---------------------------------------------------------------------------
print("\n[F03-1] Create conversation")
r = api("POST", "/api/conversations", token=user_token, json={"title": "Smoke 對話"})
check("POST /conversations → 201", r.status_code, 201)
conv_id = r.json().get("id") if r.status_code == 201 else None

if conv_id is None:
    print("  ERROR  no conversation id, skipping F03 tests")
else:
    print("\n[F03-2] List conversations")
    check("GET /conversations → 200",     api("GET", "/api/conversations", token=user_token).status_code, 200)

    print("\n[F03-3] Get conversation")
    check("GET /conversations/{id} → 200", api("GET", f"/api/conversations/{conv_id}", token=user_token).status_code, 200)

    print("\n[F03-4] Add messages")
    check("user role → 201",       api("POST", f"/api/conversations/{conv_id}/messages", token=user_token, json={"role": "user",      "content": "hi"}).status_code, 201)
    check("assistant role → 201",  api("POST", f"/api/conversations/{conv_id}/messages", token=user_token, json={"role": "assistant",  "content": "hello"}).status_code, 201)
    check("system role → 201",     api("POST", f"/api/conversations/{conv_id}/messages", token=user_token, json={"role": "system",     "content": "ctx"}).status_code, 201)

    print("\n[F03-5] Get messages")
    msgs_r = api("GET", f"/api/conversations/{conv_id}/messages", token=user_token)
    check("GET /messages → 200",   msgs_r.status_code, 200)
    if msgs_r.status_code == 200:
        roles = [m["role"] for m in msgs_r.json()]
        if roles == ["user", "assistant", "system"]:
            print("  PASS  messages ordered by createdAt (user→assistant→system)")
            passed += 1
        else:
            print(f"  FAIL  unexpected message order: {roles}")
            failed += 1

    print("\n[F03-6] Validation errors")
    check("empty title → 400",     api("POST", "/api/conversations",                                              token=user_token, json={"title": ""}).status_code, 400)
    check("invalid role → 400",    api("POST", f"/api/conversations/{conv_id}/messages", token=user_token, json={"role": "invalid", "content": "x"}).status_code, 400)

    print("\n[F03-7] 404 Not Found")
    check("GET non-existent → 404",      api("GET",  f"/api/conversations/{FAKE_ID}",          token=user_token).status_code, 404)
    check("POST msg non-existent → 404", api("POST", f"/api/conversations/{FAKE_ID}/messages", token=user_token, json={"role": "user", "content": "x"}).status_code, 404)

    print("\n[F03-8] 403 Forbidden (second user)")
    r2 = api("POST", "/api/auth/register", json={"email": f"smoke_b_{RUN}@example.com", "password": PASSWORD})
    if r2.status_code == 201:
        token_b = r2.json()["token"]
        check("GET other conv → 403",      api("GET",  f"/api/conversations/{conv_id}",          token=token_b).status_code, 403)
        check("POST msg other conv → 403", api("POST", f"/api/conversations/{conv_id}/messages", token=token_b, json={"role": "user", "content": "x"}).status_code, 403)
    else:
        print("  SKIP  could not register second user")

    if admin_token:
        print("\n[F03-9] ADMIN cross-user access")
        check("admin GET any conv → 200", api("GET", f"/api/conversations/{conv_id}", token=admin_token).status_code, 200)


# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
print("\n[Cleanup]")
cleanup(USER_EMAIL, ADMIN_EMAIL, NEW_EMAIL,
        f"x_{RUN}@e.com", f"nobody_{RUN}@e.com", f"smoke_b_{RUN}@example.com")
print("  OK    test accounts removed")


# ---------------------------------------------------------------------------
# Result
# ---------------------------------------------------------------------------
print(f"\n=== Result: {passed} passed, {failed} failed ===\n")
sys.exit(0 if failed == 0 else 1)
