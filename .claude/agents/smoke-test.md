# Smoke Test Skill

H2 核准前，對每個新 HTTP endpoint 執行 happy path + 關鍵失敗情境的端對端驗證。

## 前置條件

- App 已在本機啟動（`docker compose up db -d` → IntelliJ Run）
- port 8080 有回應
- Python 3 + `requests` 套件（`pip install requests`）

## 標準做法：執行 Python 腳本

```powershell
cd C:\Users\z5314\IdeaProjects\enterprise-ai-operation-assistant
python scripts/smoke_test.py
```

腳本涵蓋 F02（Identity/RBAC）+ F03（Conversation Management）的全部 BDD 情境：

| 區段 | 情境 |
|------|------|
| F02-1 Register validation | new email → 201, invalid email → 400, short password → 400 |
| F02-2 Duplicate email | 409 |
| F02-3 Login success | 200 |
| F02-4 Login failure | wrong password → 401, unknown email → 401 |
| F02-5 JWT auth | /users/me valid → 200, no token → 401 |
| F02-6 RBAC | ROLE_USER → 403, ROLE_ADMIN → 200, 無 password hash 外洩 |
| F02-7 Health check | /actuator/health → 200 |
| F03-1 Create conversation | POST /conversations → 201 |
| F03-2 List conversations | GET /conversations → 200 |
| F03-3 Get conversation | GET /conversations/{id} → 200 |
| F03-4 Add messages | user/assistant/system role → 201 |
| F03-5 Get messages | 200，順序驗證 |
| F03-6 Validation | empty title → 400, invalid role → 400 |
| F03-7 404 Not Found | 不存在的 conversation GET + POST msg |
| F03-8 403 Forbidden | 其他使用者的 conversation GET + POST msg |
| F03-9 Admin cross-user | ROLE_ADMIN 可存取任何 conversation |

腳本結束時自動清理測試帳號。exit code 0 = 全部通過，1 = 有失敗。

## 新增功能的 smoke test 寫法

在 `scripts/smoke_test.py` 末尾的 F03 區段之後，新增下一個功能的區段：

```python
# ---------------------------------------------------------------------------
# F0X — Your Feature Name
# ---------------------------------------------------------------------------
print("\n[F0X-1] Happy path")
r = api("POST", "/api/your-endpoint", token=user_token, json={"field": "value"})
check("POST /your-endpoint → 201", r.status_code, 201)
resource_id = r.json().get("id") if r.status_code == 201 else None

if resource_id:
    print("\n[F0X-2] Validation")
    check("empty field → 400", api("POST", "/api/your-endpoint", token=user_token, json={"field": ""}).status_code, 400)

    print("\n[F0X-3] 404")
    check("non-existent → 404", api("GET", f"/api/your-endpoint/{FAKE_ID}", token=user_token).status_code, 404)

    print("\n[F0X-4] 403 (second user)")
    if 'token_b' in dir():  # reuse token_b from F03-8 if available
        check("other user → 403", api("GET", f"/api/your-endpoint/{resource_id}", token=token_b).status_code, 403)
```

## H2 呈報格式

```
Smoke Test: 28/28 通過
  PASS  POST /api/conversations        (201)
  PASS  GET  /api/conversations        (200)
  ...
=== Result: 28 passed, 0 failed ===
```

若 Docker 不可用：在 H2 呈報中記錄「Smoke test 跳過：Docker 不可用」，作為已知限制。
