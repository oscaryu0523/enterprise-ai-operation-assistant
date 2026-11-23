# SPEC.md — F01 Foundation

> **Feature ID：** F01
> **版本：** 0.1
> **狀態：** 待 H1 核准
> **作者：** Claude Code PM／架構師
> **建立日期：** 2026-11-23
> **前置 Feature：** 無（本專案第一個 Feature）

---

## 1. 背景與目標

### 1.1 背景

新 repo `enterprise-ai-operation-assistant` 目前只有 Spring Boot 骨架，沒有資料庫、沒有 Docker Compose、CI 未驗證可執行。F01 的目的是建立可信任的開發基準：讓任何人 clone 後能在本機用一個指令啟動完整環境，CI 能在每個 PR 上自動驗證基本健康。

### 1.2 目標

1. `docker compose up` 能同時啟動 PostgreSQL 與 App，App 連線 DB 成功。
2. `GET /actuator/health` 回傳 `{"status":"UP"}`，DB 健康狀態納入檢查。
3. `./mvnw verify` 本機與 CI 皆通過。
4. GitHub `main` 分支設定 Branch Protection：PR 必須 CI 綠燈才能合併。

### 1.3 不在本次範圍

- 任何業務邏輯或 API 端點（F02 起才加）。
- Flyway Migration（F02 加入第一張資料表時才建立）。
- 正式環境部署或雲端基礎設施。
- Spring Security / JWT（F02）。

---

## 2. 使用情境

| Persona | 情境 |
|---|---|
| 開發者（本機）| `docker compose up -d` → `./mvnw verify` → 全部通過 |
| 開發者（PR）| 推分支、開 PR → GitHub Actions CI 自動執行、全部綠燈 |
| 任何人 | `GET /actuator/health` → `{"status":"UP","components":{"db":{"status":"UP"}}}` |

---

## 3. BDD 驗收條件

### 3.1 App 啟動成功

```
Given  docker compose up -d 已執行（PostgreSQL 與 App 容器都在跑）
When   等待 App 健康狀態就緒（最多 60 秒）
Then   App 容器狀態為 running 且沒有 exit
And    Log 中出現 "Started AiAssistantApplication"
```

### 3.2 健康檢查端點

```
Given  App 已啟動且連線 PostgreSQL 成功
When   GET http://localhost:8080/actuator/health
Then   HTTP 200
And    body.status = "UP"
And    body.components.db.status = "UP"
```

### 3.3 資料庫連線失敗時健康狀態降級

```
Given  PostgreSQL 容器停止（docker compose stop db）
When   GET http://localhost:8080/actuator/health
Then   HTTP 503
And    body.status = "DOWN"
And    body.components.db.status = "DOWN"
```

### 3.4 本機測試通過

```
Given  docker compose up -d db（僅啟動 DB，供 Testcontainers 或直連使用）
When   ./mvnw verify
Then   BUILD SUCCESS
And    無測試失敗
And    Spotless check 通過
```

### 3.5 CI 在 PR 上自動執行

```
Given  開發者推送分支並開 PR 至 main
When   GitHub Actions 觸發 ci.yml
Then   "Build and test" job 執行並回報結果
And    CI 失敗時無法合併（Branch Protection 擋住）
```

---

## 4. 技術架構與選型

### 4.1 新增依賴（pom.xml）

| 依賴 | 用途 | 版本策略 |
|---|---|---|
| `spring-boot-starter-data-jpa` | JPA / Hibernate（建立 DataSource，健康檢查需要） | Spring Boot BOM |
| `postgresql` (runtime) | PostgreSQL JDBC Driver | Spring Boot BOM |
| `spring-boot-testcontainers` | 整合測試用真實 PostgreSQL | Spring Boot BOM |
| `testcontainers-postgresql` | Testcontainers PostgreSQL module | Testcontainers BOM |

### 4.2 Docker Compose

```
docker-compose.yml
├── db        PostgreSQL 17, port 5432, volume: pgdata
└── app       ./mvnw spring-boot:run 或 jar，port 8080，depends_on db healthy
```

- DB 環境變數：`POSTGRES_DB=aiassistant`, `POSTGRES_USER=aiassistant`, `POSTGRES_PASSWORD=aiassistant`（本機開發用，非正式環境）
- App 環境變數：`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`

### 4.3 Actuator 設定

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: always   # F01 開發階段全開，F02 加 Security 後改 when-authorized
```

### 4.4 Testcontainers 整合測試

- `@SpringBootTest` + `@Testcontainers`：自動啟動 PostgreSQL 容器
- 驗證 DataSource 連線與 `/actuator/health` 回傳 `UP`
- 使用 `spring-boot-testcontainers` 的 `@ServiceConnection` 自動注入連線設定

### 4.5 CI 更新

現有 `ci.yml` 已有 `./mvnw verify`，需確認：
- Testcontainers 在 GitHub Actions `ubuntu-latest` 上可用（Docker 已預裝）
- Spotless check 步驟已存在

---

## 5. 錯誤行為

| 情境 | 預期行為 |
|---|---|
| DB 容器未啟動，App 啟動 | App 啟動失敗（DataSource 連線逾時），容器 exit |
| DB 啟動後斷線 | `/actuator/health` 回傳 503 DOWN |
| CI 中 Docker 不可用 | 測試失敗並回報，CI 紅燈 |

---

## 6. 安全要求

- Docker Compose 的 DB 密碼為本機開發用佔位值，不得與正式環境相同。
- `.env` 檔案加入 `.gitignore`，不提交任何機密。
- CI 不使用 Repository secrets（F01 不需要外部服務）。

---

## 7. 非功能需求

| 指標 | 目標 |
|---|---|
| `docker compose up` 到 App 健康就緒 | ≤ 90 秒（首次需 pull image） |
| CI 執行時間 | ≤ 5 分鐘 |
| `./mvnw verify` 本機執行時間 | ≤ 3 分鐘（Testcontainers 含 image pull） |

---

## 8. 完成定義（DoD）

- [ ] `docker compose up -d` 後 App 健康就緒
- [ ] `GET /actuator/health` 回傳 `{"status":"UP"}` 且含 `db` 元件
- [ ] DB 停止時 `/actuator/health` 回傳 503
- [ ] `./mvnw verify` 本機通過（含 Testcontainers 整合測試與 Spotless）
- [ ] GitHub Actions CI 綠燈
- [ ] GitHub `main` Branch Protection 設定完成（PR 必須 CI 通過）
- [ ] Claude Code QA（全新對話）逐條驗收，無 Critical／Major
- [ ] 人類 H3 核准合併

---

## 9. 已知限制

| 項目 | 說明 |
|---|---|
| Flyway | F01 不建立任何 Migration，DB 為空（Spring Boot 設 `ddl-auto: none`） |
| App Docker image | F01 用 `spring-boot:run` 或直接跑 jar，不建立正式 Dockerfile |
| Branch Protection | 需手動在 GitHub UI 或用 `gh` 設定，CI 無法自動設定 |

---

*本 SPEC.md 為 F01 規格草稿，待使用者 H1 核准後 Claude Code 開始實作。*
