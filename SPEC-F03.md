# SPEC-F03.md — Conversation Management

> **Feature ID：** F03
> **版本：** 0.1
> **狀態：** 已實作（PR #3）
> **作者：** Claude Code PM／架構師
> **建立日期：** 2026-11-25
> **前置 Feature：** F02（身分驗證與 RBAC）

---

## 1. 背景與目標

### 1.1 背景

F02 完成身分驗證後，系統已能辨識「誰在呼叫」，但還沒有地方儲存對話內容。F03 建立對話與訊息的持久化層，讓後續 F04 AI Gateway 有地方放對話上下文，實現多輪對話。

### 1.2 目標

1. 已認證使用者可建立對話（Conversation）、列出自己的對話、查詢單一對話。
2. 可在對話中新增訊息（Message），並查詢對話的完整訊息列表。
3. 存取控制：使用者只能存取自己的對話；ROLE_ADMIN 可存取所有使用者的對話。
4. Flyway V2 migration 建立 `conversations` 與 `messages` 資料表。
5. 稽核日誌：`CONVERSATION_CREATED` 事件寫入 `audit_log`。

### 1.3 不在本次範圍

- 實際 AI 回覆（訊息內容由呼叫端提供，F04 AI Gateway 才接入模型）
- Streaming 回應
- 對話封存／刪除
- 分頁（Pagination）——F03 先回傳全部，有資料量需求再加
- 訊息內容的 Prompt Injection 防護（F04 Agent Runtime 負責）
- WebSocket / 即時推播

---

## 2. 使用情境

| Persona | 情境 |
|---|---|
| 一般使用者 | 建立對話 → 新增訊息 → 查詢對話訊息列表 |
| 一般使用者 | 列出自己所有對話，繼續之前的對話 |
| 一般使用者 | 嘗試存取其他使用者的對話 → 被拒絕 |
| ADMIN | 查詢任意使用者的對話（維運稽核用途） |
| 未認證請求 | 任何端點 → 401 |

---

## 3. BDD 驗收條件

### 3.1 建立對話

```
Given  已認證使用者（JWT 有效）
When   POST /api/conversations，body: {"title": "我的第一個對話"}
Then   HTTP 201
And    body.id 為自動生成的 UUID
And    body.title = "我的第一個對話"
And    body.ownerId = 目前使用者 ID
And    body.status = "ACTIVE"
And    body.createdAt 為 ISO 8601 時間戳
And    audit_log 寫入 CONVERSATION_CREATED 事件（userId、conversationId）
```

```
Given  已認證使用者
When   POST /api/conversations，body: {"title": ""}（空標題）
Then   HTTP 400
And    body.code = "VALIDATION_ERROR"
And    body.message 說明 title 不能為空
```

```
Given  未認證請求（無 Authorization header）
When   POST /api/conversations
Then   HTTP 401
And    body.code = "UNAUTHORIZED"
```

### 3.2 列出對話

```
Given  使用者 A 有 2 個對話，使用者 B 有 1 個對話
When   使用者 A：GET /api/conversations
Then   HTTP 200
And    body 是陣列，長度 = 2
And    陣列中只有使用者 A 自己的對話（不包含使用者 B 的對話）
And    每筆含 id、title、status、createdAt（不含 messages）
```

```
Given  使用者無任何對話
When   GET /api/conversations
Then   HTTP 200
And    body = []
```

### 3.3 查詢單一對話

```
Given  使用者 A 擁有對話 C1
When   使用者 A：GET /api/conversations/{C1.id}
Then   HTTP 200
And    body.id = C1.id
And    body.messages 為空陣列（尚無訊息）
```

```
Given  使用者 A 嘗試存取使用者 B 的對話 C2
When   使用者 A：GET /api/conversations/{C2.id}
Then   HTTP 403
And    body.code = "FORBIDDEN"
```

```
Given  任意已認證使用者
When   GET /api/conversations/{不存在的 id}
Then   HTTP 404
And    body.code = "NOT_FOUND"
```

### 3.4 新增訊息

```
Given  使用者擁有對話 C1
When   POST /api/conversations/{C1.id}/messages
       body: {"role": "user", "content": "請說明 JWT 的用途"}
Then   HTTP 201
And    body.id 為自動生成 UUID
And    body.conversationId = C1.id
And    body.role = "user"
And    body.content = "請說明 JWT 的用途"
And    body.createdAt 為 ISO 8601 時間戳
```

```
Given  使用者擁有對話 C1
When   POST /api/conversations/{C1.id}/messages
       body: {"role": "assistant", "content": "JWT 是..."}
Then   HTTP 201
And    body.role = "assistant"
```

```
Given  使用者擁有對話 C1
When   POST /api/conversations/{C1.id}/messages
       body: {"role": "invalid_role", "content": "test"}
Then   HTTP 400
And    body.code = "VALIDATION_ERROR"
```

```
Given  使用者 A 嘗試新增訊息至使用者 B 的對話 C2
When   POST /api/conversations/{C2.id}/messages，body 合法
Then   HTTP 403
And    body.code = "FORBIDDEN"
```

### 3.5 查詢訊息列表

```
Given  對話 C1 有 3 則訊息（依建立順序：user、assistant、user）
When   GET /api/conversations/{C1.id}/messages
Then   HTTP 200
And    body 是陣列，長度 = 3
And    訊息依 createdAt 升序排列
And    每筆含 id、conversationId、role、content、createdAt
```

### 3.6 ADMIN 跨使用者存取

```
Given  ROLE_ADMIN 使用者
When   GET /api/conversations/{任意使用者的對話 id}
Then   HTTP 200（不論擁有者是誰）
```

```
Given  ROLE_ADMIN 使用者
When   POST /api/conversations/{任意使用者的對話 id}/messages，body 合法
Then   HTTP 201（ADMIN 可代為新增訊息）
```

---

## 4. 資料模型

### 4.1 Conversation

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | UUID | PK，自動生成 |
| owner_id | BIGINT | FK → users.id（NOT NULL） |
| title | VARCHAR(255) | 不可為空 |
| status | VARCHAR(20) | `ACTIVE`（預設）；保留未來擴充 |
| created_at | TIMESTAMPTZ | 建立時間 |
| updated_at | TIMESTAMPTZ | 最後更新時間（訊息新增時更新） |

### 4.2 Message

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | UUID | PK，自動生成 |
| conversation_id | UUID | FK → conversations.id（NOT NULL） |
| role | VARCHAR(20) | `user` / `assistant` / `system` |
| content | TEXT | 訊息內容（不可為空） |
| created_at | TIMESTAMPTZ | 建立時間 |

### 4.3 Flyway V2 migration

新增 `V2__conversations_messages.sql`：
- 建立 `conversations` 與 `messages` 資料表
- 在 `conversations.owner_id` 建立 INDEX
- 在 `messages.conversation_id` 建立 INDEX

---

## 5. API 規格

所有端點需 `Authorization: Bearer <JWT>`，否則 HTTP 401。

### 5.1 建立對話

```
POST /api/conversations
Request:  {"title": "string（1–255 字元，必填）"}
Response 201: ConversationResponse（含 id、title、ownerId、status、createdAt）
Response 400: {"code": "VALIDATION_ERROR", "message": "..."}
Response 401: {"code": "UNAUTHORIZED"}
```

### 5.2 列出對話

```
GET /api/conversations
Response 200: ConversationResponse[]（只含自己的；ADMIN 含所有）
  每筆：id、title、ownerId、status、createdAt（不含 messages）
Response 401: {"code": "UNAUTHORIZED"}
```

### 5.3 查詢單一對話

```
GET /api/conversations/{id}
Response 200: ConversationResponse（含 messages 陣列）
Response 401: {"code": "UNAUTHORIZED"}
Response 403: {"code": "FORBIDDEN"}
Response 404: {"code": "NOT_FOUND"}
```

### 5.4 新增訊息

```
POST /api/conversations/{id}/messages
Request:  {"role": "user|assistant|system"（必填）, "content": "string（1–32000 字元，必填）"}
Response 201: MessageResponse（id、conversationId、role、content、createdAt）
Response 400: {"code": "VALIDATION_ERROR", "message": "..."}
Response 401: {"code": "UNAUTHORIZED"}
Response 403: {"code": "FORBIDDEN"}
Response 404: {"code": "NOT_FOUND"}（對話不存在）
```

### 5.5 查詢訊息列表

```
GET /api/conversations/{id}/messages
Response 200: MessageResponse[]（依 createdAt 升序）
Response 401: {"code": "UNAUTHORIZED"}
Response 403: {"code": "FORBIDDEN"}
Response 404: {"code": "NOT_FOUND"}
```

---

## 6. 架構設計

### 6.1 新增 Package

```
com.enterprise.aiassistant.conversation/
  Conversation.java          (JPA Entity)
  Message.java               (JPA Entity)
  ConversationRepository.java
  MessageRepository.java
  ConversationService.java
  ConversationController.java
  CreateConversationRequest.java  (record)
  AddMessageRequest.java          (record)
  ConversationResponse.java       (record)
  MessageResponse.java            (record)
  ConversationNotFoundException.java
```

### 6.2 存取控制邏輯

`ConversationService` 在每次查詢與修改前，驗證：

```
呼叫端 userId == conversation.ownerId
  OR 呼叫端 role == ROLE_ADMIN
→ 通過，否則拋 ForbiddenException（HTTP 403）
```

從 JWT SecurityContext 取得 userId 與 role，不信任 request body 裡的任何身分資訊。

### 6.3 稽核

`CONVERSATION_CREATED` 事件呼叫現有 `AuditService.record()`，傳入 userId 與 conversationId（放入 details 欄位）。訊息新增不寫 audit event（量太大；F07 可觀測性再評估）。

### 6.4 AuditEvent 擴充

在既有 `AuditEvent` enum 新增 `CONVERSATION_CREATED`。

---

## 7. 錯誤行為

所有錯誤回應使用 `ErrorResponse` record，格式：`{"code": "...", "message": "...", "timestamp": "<ISO 8601>"}`。

| 情境 | HTTP | code |
|------|------|------|
| 無 JWT 或 JWT 無效 | 401 | UNAUTHORIZED |
| JWT 有效但無權存取該對話 | 403 | FORBIDDEN |
| 對話 id 不存在 | 404 | NOT_FOUND |
| title 為空或超長 | 400 | VALIDATION_ERROR |
| role 非合法值 | 400 | VALIDATION_ERROR |
| content 為空或超長（>32000） | 400 | VALIDATION_ERROR |

---

## 8. 安全要求

- 存取控制必須在 Service 層以後端邏輯執行，不依賴 JWT claims 裡的 conversationId。
- content 欄位視為不可信輸入，Service 層不執行任何業務邏輯解析（Prompt Injection 防護由 F04 負責）。
- 不在 audit_log 或應用程式日誌中記錄 message content（避免洩漏敏感對話）。

---

## 9. 非功能需求

| 指標 | 目標 |
|------|------|
| `POST /api/conversations` 回應時間 | ≤ 200ms（本機，含 DB） |
| `GET /api/conversations/{id}/messages` | ≤ 300ms（100 則訊息以內） |
| content 最大長度 | 32000 字元（供 F04 Context 組裝用） |

---

## 10. 完成定義（DoD）

- [ ] Flyway V2 migration 通過
- [ ] 所有 BDD 驗收條件有對應測試且通過
- [ ] `./mvnw verify` BUILD SUCCESS（含 Spotless）
- [ ] CI 綠燈
- [ ] Smoke test 腳本驗證所有 Happy Path 與主要 Error Path
- [ ] QA（全新對話）驗收，無 Critical／Major
- [ ] H3 使用者核准合併

---

## 11. 已知限制

| 項目 | 說明 |
|------|------|
| 訊息內容無 AI 回覆 | F03 只做持久化；AI 回應由 F04 負責 |
| 無分頁 | F03 回傳全部，有需求再加 |
| 訊息無法修改或刪除 | F03 只做新增與查詢 |
| content 長度限制 | 32000 字元，配合後續 Context 組裝需求訂定，可調整 |

---

*本 SPEC-F03.md 為草稿，待使用者 H1 核准後 Claude Code 開始實作。*
