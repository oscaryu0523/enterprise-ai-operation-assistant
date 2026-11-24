# F02 Identity/RBAC API Smoke Test
# Usage: .\scripts\smoke-test-f02.ps1
# Requires: App on localhost:8080, docker compose db running

$BASE = "http://localhost:8080"
$PASS = 0
$FAIL = 0
$RUN  = Get-Random

$USER_EMAIL  = "smoke_user_$RUN@example.com"
$ADMIN_EMAIL = "smoke_admin_$RUN@example.com"
$NEW_EMAIL   = "smoke_new_$RUN@example.com"
$SHORT_EMAIL = "smoke_short_$RUN@example.com"
$PASSWORD    = "SmokeTest123"

function Assert-Status($label, $actual, $expected) {
  if ($actual -eq $expected) {
    Write-Host "  PASS  $label (HTTP $actual)" -ForegroundColor Green
    $script:PASS++
  } else {
    Write-Host "  FAIL  $label (expected $expected, got $actual)" -ForegroundColor Red
    $script:FAIL++
  }
}

function Invoke-Api($method, $path, $body, $token) {
  $headers = @{ "Content-Type" = "application/json" }
  if ($token) { $headers["Authorization"] = "Bearer $token" }
  try {
    $params = @{ Uri="$BASE$path"; Method=$method; Headers=$headers; ErrorAction="Stop" }
    if ($body) { $params["Body"] = ($body | ConvertTo-Json) }
    $resp = Invoke-WebRequest @params -UseBasicParsing
    return @{ Status=[int]$resp.StatusCode; Body=($resp.Content | ConvertFrom-Json); Raw=$resp.Content }
  } catch {
    $code = $_.Exception.Response.StatusCode
    return @{ Status=[int]$code; Body=$null; Raw="" }
  }
}

Write-Host ""
Write-Host "=== F02 Smoke Test (run=$RUN) ===" -ForegroundColor Cyan

# --- Setup ---
Write-Host ""
Write-Host "[Setup] Creating test accounts..."
$r = Invoke-Api POST "/api/auth/register" @{ email=$USER_EMAIL; password=$PASSWORD; department="SmokeTest" }
if ($r.Status -ne 201) { Write-Host "  ERROR  user register failed ($($r.Status)), aborting" -ForegroundColor Red; exit 1 }
$userToken = $r.Body.access_token

$r2 = Invoke-Api POST "/api/auth/register" @{ email=$ADMIN_EMAIL; password=$PASSWORD }
if ($r2.Status -ne 201) { Write-Host "  ERROR  admin register failed ($($r2.Status)), aborting" -ForegroundColor Red; exit 1 }

docker compose exec -T db psql -U aiassistant -d aiassistant -c "UPDATE users SET role='ROLE_ADMIN' WHERE email='$ADMIN_EMAIL';" | Out-Null

$rAdmin = Invoke-Api POST "/api/auth/login" @{ email=$ADMIN_EMAIL; password=$PASSWORD }
if ($rAdmin.Status -ne 200) { Write-Host "  ERROR  admin login failed, aborting" -ForegroundColor Red; exit 1 }
$adminToken = $rAdmin.Body.access_token
Write-Host "  OK    user=$USER_EMAIL" -ForegroundColor DarkGray
Write-Host "  OK    admin=$ADMIN_EMAIL" -ForegroundColor DarkGray

# --- BDD 3.1: Register ---
Write-Host ""
Write-Host "[BDD 3.1] Register"
$r = Invoke-Api POST "/api/auth/register" @{ email=$NEW_EMAIL; password=$PASSWORD }
Assert-Status "POST /register (new email -> 201)" $r.Status 201
$r = Invoke-Api POST "/api/auth/register" @{ email="not-an-email"; password=$PASSWORD }
Assert-Status "POST /register (invalid email -> 400)" $r.Status 400
$r = Invoke-Api POST "/api/auth/register" @{ email=$SHORT_EMAIL; password="short" }
Assert-Status "POST /register (password too short -> 400)" $r.Status 400

# --- BDD 3.2: Duplicate email ---
Write-Host ""
Write-Host "[BDD 3.2] Duplicate email"
$r = Invoke-Api POST "/api/auth/register" @{ email=$USER_EMAIL; password=$PASSWORD }
Assert-Status "POST /register (duplicate email -> 409)" $r.Status 409

# --- BDD 3.3: Login success ---
Write-Host ""
Write-Host "[BDD 3.3] Login success"
$r = Invoke-Api POST "/api/auth/login" @{ email=$USER_EMAIL; password=$PASSWORD }
Assert-Status "POST /login (correct password -> 200)" $r.Status 200

# --- BDD 3.4: Login failure ---
Write-Host ""
Write-Host "[BDD 3.4] Login failure"
$r = Invoke-Api POST "/api/auth/login" @{ email=$USER_EMAIL; password="wrongpassword" }
Assert-Status "POST /login (wrong password -> 401)" $r.Status 401
$r = Invoke-Api POST "/api/auth/login" @{ email="nobody_$RUN@example.com"; password="x" }
Assert-Status "POST /login (unknown email -> same 401)" $r.Status 401

# --- BDD 3.5: JWT auth ---
Write-Host ""
Write-Host "[BDD 3.5] JWT auth"
$r = Invoke-Api GET "/api/users/me" $null $userToken
Assert-Status "GET /users/me (valid token -> 200)" $r.Status 200

# --- BDD 3.6: No JWT ---
Write-Host ""
Write-Host "[BDD 3.6] No JWT"
$r = Invoke-Api GET "/api/users/me" $null $null
Assert-Status "GET /users/me (no token -> 401)" $r.Status 401

# --- BDD 3.7: ROLE_USER on admin ---
Write-Host ""
Write-Host "[BDD 3.7] RBAC - user access admin"
$r = Invoke-Api GET "/api/admin/users" $null $userToken
Assert-Status "GET /admin/users (ROLE_USER -> 403)" $r.Status 403

# --- BDD 3.8: ROLE_ADMIN on admin ---
Write-Host ""
Write-Host "[BDD 3.8] RBAC - admin access admin"
$r = Invoke-Api GET "/api/admin/users" $null $adminToken
Assert-Status "GET /admin/users (ROLE_ADMIN -> 200)" $r.Status 200
if (-not ($r.Raw -match "password_hash|passwordHash")) {
  Write-Host "  PASS  admin list does not expose password_hash" -ForegroundColor Green
  $script:PASS++
} else {
  Write-Host "  FAIL  admin list leaks password_hash!" -ForegroundColor Red
  $script:FAIL++
}

# --- BDD 3.9: Health check ---
Write-Host ""
Write-Host "[BDD 3.9] Health check"
$r = Invoke-Api GET "/actuator/health" $null $null
Assert-Status "GET /actuator/health (no auth -> 200)" $r.Status 200

# --- Cleanup ---
Write-Host ""
Write-Host "[Cleanup] Deleting test accounts..."
$emails = "'$USER_EMAIL','$ADMIN_EMAIL','$NEW_EMAIL','$SHORT_EMAIL'"
docker compose exec -T db psql -U aiassistant -d aiassistant -c "DELETE FROM users WHERE email IN ($emails);" | Out-Null
Write-Host "  OK    test accounts removed" -ForegroundColor DarkGray

# --- Result ---
Write-Host ""
if ($FAIL -eq 0) {
  Write-Host "=== Result: $PASS passed, $FAIL failed ===" -ForegroundColor Green
} else {
  Write-Host "=== Result: $PASS passed, $FAIL failed ===" -ForegroundColor Red
  exit 1
}
