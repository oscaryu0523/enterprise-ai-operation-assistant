# SPEC-F02.md — Identity & RBAC

> **Feature ID：** F02
> **版本：** 0.1
> **狀態：** 待 H1 核准
> **作者：** Claude Code PM／架構師
> **建立日期：** 2026-11-24
> **前置 Feature：** F01（健康檢查、Docker Compose、CI）

---

## 1. 背景與目標

### 1.1 背景

F01 完成後，專案有基礎基礎設施但無任何身分驗證。F02 建立使用者帳號系統與 JWT RBAC，讓後續所有 Feature（RAG、對話、工具呼叫）能以「誰在呼叫、有哪些權限」為基準做存取控制。

### 1.2 目標

1. 使用者可以用 email + password 註冊帳號，密碼以 BCrypt 儲存。
2. 登入成功後取得 JWT，之後每個請求帶 `Authorization: Bearer <token>` 通過驗證。
3. RBAC：三種角色 `ROLE_USER`、`ROLE_OPS`、`ROLE_ADMIN`，各端點有最低角色要求。
4. 稽核日誌：`LOGIN_SUCCESS`、`LOGIN_FAILED`、`ACCESS_DENIED` 事件自動寫入 `audit_log`。
5. Flyway V1 migration 建立 `users` 與 `audit_log` 資料表。

### 1.3 不在本次範圍

- Refresh Token / Token 吊銷（F02 只做 Access Token，有效期 24 小時）
- OAuth2 / OIDC / 社群登入
- 使用者自行修改密碼或個人資料
- 管理員 UI
- 電子郵件驗證
- F03 RAG、F04 對話、F05 成本、F06 Gmail（後續 Feature）

---

## 2. 使用情境

| Persona | 情境 |
|---|---|
| 新使用者 | `POST /api/auth/register` 建立帳號 → 取得 JWT → 呼叫 `GET /api/users/me` 查看個人資料 |
| 一般使用者 | `POST /api/auth/login` → JWT → 呼叫受保護端點 |
| OPS 使用者 | 與 ROLE_USER 相同，另可呼叫 OPS 層級端點（F03 以後使用） |
| 管理員 | `GET /api/admin/users` 查看所有使用者清單 |
| 未認證請求 | 呼叫受保護端點 → 401 Unauthorized |
| 權限不足 | ROLE_USER 呼叫管理員端點 → 403 Forbidden |

---

## 3. BDD 驗收條件

### 3.1 使用者註冊成功

```
Given  POST /api/auth/register { "email": "user@example.com", "password": "P@ssw0rd123", "department": "IT" }
When   email 未被使用
Then   HTTP 201 Created
And    response body 含 { "token": "<jwt>", "email": "user@example.com", "role": "ROLE_USER" }
And   users 資料表新增一筆記錄，password_hash 為 BCrypt（不含明文密碼）
And    audit_log 寫入 event=REGISTER，user_email=user@example.com
```

### 3.2 重複 email 註冊失敗

```
Given  email "dup@example.com" 已存在
When   POST /api/auth/register { "email": "dup@example.com", ... }
Then   HTTP 409 Conflict
And    response body 含 { "error": "EMAIL_ALREADY_EXISTS" }
And    audit_log 不寫入新記錄
```

### 3.3 登入成功

```
Given  使用者 "login@example.com" 存在且 enabled=true
When   POST /api/auth/login { "email": "login@example.com", "password": "正確密碼" }
Then   HTTP 200 OK
And    response body 含 { "token": "<jwt>", "email": "login@example.com", "role": "ROLE_USER" }
And    audit_log 寫入 event=LOGIN_SUCCESS，user_email=login@example.com，ip_address=<請求 IP>
```

### 3.4 密碼錯誤登入失敗

```
Given  使用者 "fail@example.com" 存在
When   POST /api/auth/login { "email": "fail@example.com", "password": "錯誤密碼" }
Then   HTTP 401 Unauthorized
And    response body 含 { "error": "INVALID_CREDENTIALS" }
And    audit_log 寫入 event=LOGIN_FAILED，user_email=fail@example.com，ip_address=<請求 IP>
```

### 3.5 JWT 驗證通過，取得個人資料

```
Given  有效 JWT（ROLE_USER 以上）
When   GET /api/users/me，Header: Authorization: Bearer <token>
Then   HTTP 200 OK
And    response body 含 { "id": ..., "email": ..., "role": ..., "department": ... }
```

### 3.6 無 JWT 存取受保護端點 → 401

```
Given  沒有 Authorization header
When   GET /api/users/me
Then   HTTP 401 Unauthorized
And    response body 含 { "error": "UNAUTHORIZED" }
And    audit_log 不寫入記錄（未認證事件不追蹤至使用者層級）
```

### 3.7 ROLE_USER 存取管理端點 → 403

```
Given  有效 JWT（ROLE_USER）
When   GET /api/admin/users，Header: Authorization: Bearer <token>
Then   HTTP 403 Forbidden
And    response body 含 { "error": "ACCESS_DENIED" }
And    audit_log 寫入 event=ACCESS_DENIED，user_email=<token 裡的 email>，ip_address=<請求 IP>
```

### 3.8 ROLE_ADMIN 存取管理端點成功

```
Given  有效 JWT（ROLE_ADMIN）
When   GET /api/admin/users，Header: Authorization: Bearer <token>
Then   HTTP 200 OK
And    response body 為使用者清單陣列（含 id, email, role, department, enabled, createdAt）
And    密碼欄位不出現在 response 中
```

### 3.9 Actuator health 不需認證

```
Given  沒有 Authorization header
When   GET /actuator/health
Then   HTTP 200 OK（與 F01 行為一致，不受 Spring Security 攔截）
```

### 3.10 Flyway migration 在 App 啟動時自動執行

```
Given  全新資料庫（無任何資料表）
When   App 啟動
Then   users 資料表存在且結構正確
And    audit_log 資料表存在且結構正確
And    flyway_schema_history 記錄 V1 migration 已執行
```

---

## 4. 技術架構與選型

### 4.1 新增依賴（pom.xml）

| 依賴 | 用途 | 版本 |
|---|---|---|
| `spring-boot-starter-security` | Spring Security 整合 | Spring Boot BOM |
| `spring-boot-starter-validation` | `@Valid` 驗證 request body | Spring Boot BOM |
| `io.jsonwebtoken:jjwt-api` | JWT 生成與解析 API | 0.12.6 |
| `io.jsonwebtoken:jjwt-impl` | JWT 實作（runtime） | 0.12.6 |
| `io.jsonwebtoken:jjwt-jackson` | JWT Jackson 序列化（runtime） | 0.12.6 |
| `org.flywaydb:flyway-core` | Schema migration | Spring Boot BOM |
| `org.flywaydb:flyway-database-postgresql` | Flyway PostgreSQL 支援 | Spring Boot BOM |

### 4.2 資料庫 Schema（Flyway V1）

檔案：`src/main/resources/db/migration/V1__users_audit_log.sql`

```sql
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(50)  NOT NULL DEFAULT 'ROLE_USER',
    department      VARCHAR(100),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    event        VARCHAR(100)  NOT NULL,
    user_id      BIGINT        REFERENCES users(id) ON DELETE SET NULL,
    user_email   VARCHAR(255),
    ip_address   VARCHAR(45),
    details      JSONB,
    occurred_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_user_id     ON audit_log(user_id);
CREATE INDEX idx_audit_log_occurred_at ON audit_log(occurred_at);
```

### 4.3 套件結構

```
com.enterprise.aiassistant
├── auth
│   ├── AuthController        POST /api/auth/register, POST /api/auth/login
│   ├── AuthService           業務邏輯：register, login
│   ├── AuthRequest           record { email, password, department? }
│   ├── LoginRequest          record { email, password }
│   └── AuthResponse          record { token, email, role }
├── user
│   ├── User                  @Entity → users 資料表
│   ├── UserRepository        JpaRepository<User, Long>
│   ├── UserController        GET /api/users/me, GET /api/admin/users
│   └── UserResponse          record { id, email, role, department, enabled, createdAt }
├── audit
│   ├── AuditLog              @Entity → audit_log 資料表
│   ├── AuditLogRepository    JpaRepository<AuditLog, Long>
│   └── AuditService          record(event, userId, userEmail, ip, details)
└── security
    ├── JwtService            generateToken, validateToken, extractEmail
    ├── JwtAuthFilter         OncePerRequestFilter：讀 header → 驗證 → 設 SecurityContext
    ├── SecurityConfig        SecurityFilterChain：路由規則、CORS、CSRF 設定
    └── UserDetailsServiceImpl loadUserByUsername → UserDetails
```

### 4.4 JWT 設計

- 演算法：`HS256`，Secret key 從環境變數 `JWT_SECRET` 讀取（≥256 bits，本機用佔位值）
- Claims：`sub`（email）、`role`、`iat`、`exp`（24 小時）
- 本機預設 secret：`app.jwt.secret=dev-secret-do-not-use-in-production-change-me-now!` 加入 `application.yml`

### 4.5 路由權限規則

| 路徑 | 方法 | 最低角色 |
|---|---|---|
| `/api/auth/**` | 任何 | 無（公開） |
| `/actuator/health` | GET | 無（公開） |
| `/api/users/me` | GET | `ROLE_USER` |
| `/api/admin/**` | 任何 | `ROLE_ADMIN` |

### 4.6 錯誤回應格式

```json
{ "error": "ERROR_CODE", "message": "人類可讀說明", "timestamp": "ISO-8601" }
```

標準 error codes：`EMAIL_ALREADY_EXISTS`、`INVALID_CREDENTIALS`、`UNAUTHORIZED`、`ACCESS_DENIED`、`VALIDATION_ERROR`

### 4.7 EDD 評估資料集

F02 包含模型行為（JWT 有效性、密碼驗證），需定義固定評估案例：

| 案例 ID | 輸入 | 預期結果 |
|---|---|---|
| EDD-F02-01 | 合法 JWT，未過期 | `JwtService.validateToken` → true |
| EDD-F02-02 | 過期 JWT（exp < now） | `validateToken` → false |
| EDD-F02-03 | 竄改 signature 的 JWT | `validateToken` → false |
| EDD-F02-04 | BCrypt hash + 正確明文 | `AuthService.login` 通過 |
| EDD-F02-05 | BCrypt hash + 錯誤明文 | `AuthService.login` 拋出 `InvalidCredentialsException` |

每次 CI 執行的 `JwtServiceTest` 與 `AuthServiceTest` 覆蓋以上案例，確保每次 build 均可量測。

---

## 5. 錯誤行為

| 情境 | 預期行為 |
|---|---|
| email 格式不合法（無 @） | HTTP 400，`VALIDATION_ERROR` |
| password 長度不足（< 8 字元） | HTTP 400，`VALIDATION_ERROR` |
| JWT secret 環境變數未設 | App 啟動失敗，明確錯誤訊息 |
| DB 斷線時呼叫受保護端點 | HTTP 503，健康端點同步降級 |
| 帳號 enabled=false | 登入回傳 401，`INVALID_CREDENTIALS`（不揭露帳號被停用） |

---

## 6. 安全要求

- 密碼不以明文儲存或在任何 log／response 中出現。
- JWT Secret 從環境變數讀取，不 hardcode 至程式碼或 commit 至 git。
- `/actuator/health` 在 F02 後改為 `show-details: when_authorized`（僅認證使用者才看到 DB 元件）；`/actuator/health` 本身仍公開回傳頂層 `status`。
- audit_log.details 中不儲存密碼或 token 明文。
- SQL 操作全部透過 JPA/Hibernate，不使用 raw JDBC string concatenation（防 SQL injection）。
- Prompt Injection 無關（F02 無 LLM 呼叫）。

---

## 7. 非功能需求

| 指標 | 目標 |
|---|---|
| POST /api/auth/login 回應時間 | P95 ≤ 500ms（本機 Docker Compose） |
| Flyway migration 執行時間 | ≤ 5 秒 |
| 測試覆蓋率（業務邏輯層） | unit + integration 覆蓋 BDD 3.1–3.10 全部情境 |

---

## 8. 完成定義（DoD）

- [ ] Flyway V1 migration 執行成功（users + audit_log）
- [ ] BDD 3.1–3.10 全部驗收通過
- [ ] EDD-F02-01 至 EDD-F02-05 全部通過
- [ ] `./mvnw verify` 本機與 CI 皆通過（含 Spotless）
- [ ] response body 中不出現 `password_hash`
- [ ] JWT secret 不出現在任何已 commit 的程式碼中（使用佔位值並說明）
- [ ] Claude Code QA（全新對話）逐條驗收，無 Critical／Major
- [ ] H3 使用者核准合併

---

## 9. 已知限制

| 項目 | 說明 |
|---|---|
| Refresh Token | F02 無 Refresh Token；JWT 固定 24 小時，到期需重新登入 |
| Token 吊銷 | F02 無 blacklist；登出僅為 client 端刪除 token |
| 密碼強度驗證 | F02 只驗 minLength=8；複雜度規則留 F-future |
| actuator health details | F02 改為 `when_authorized`；未認證請求只看到頂層 status |

---

*本 SPEC-F02.md 為 F02 規格草稿，待使用者 H1 核准後 Claude Code 開始實作。*
