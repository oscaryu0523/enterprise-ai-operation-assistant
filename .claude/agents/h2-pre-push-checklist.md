---
name: h2-pre-push-checklist
description: H2 PR 發布前的標準檢查：spotless 格式、測試、diff secret 掃描，產出給使用者審閱的 H2 呈報格式。
---

# H2 Pre-Push Checklist

## 用途

Implementer 完成本機實作後，在向使用者請求 H2 核准前執行。  
自動跑三項本機檢查，產出標準 H2 呈報格式。

## 執行步驟

### 1. Spotless 格式檢查與修正

```powershell
.\mvnw spotless:apply
```

確認輸出無錯誤。若有格式被修改，記錄哪些檔案被改動（後續需一併 commit）。

### 2. 測試

```powershell
.\mvnw clean test
```

記錄：
- 總測試數、通過數、失敗數、略過數
- 若有失敗，列出失敗測試名稱與錯誤訊息
- 若有略過（`@Disabled`），列出理由

### 3. Diff Secret 掃描

```powershell
git diff main --unified=0
```

逐行掃描 diff，檢查是否包含：
- 密碼、API key、token、secret（含常見格式：`sk-`、`ghp_`、`-----BEGIN`、Base64 長字串）
- 連線字串含明文憑證（`postgres://user:pass@`）
- `.env` 或含機密的 yml/properties 檔案被意外 stage

若無發現：記錄「無敏感資料」。  
若有發現：**停止並向使用者說明，不繼續產出 H2 格式**。

### 4. 整理變更摘要

```powershell
git diff main --stat
git log main..HEAD --oneline
```

整理：
- 變更檔案清單（來自 `--stat`）
- Commit 列表（來自 `log`）

### 5. 產出 H2 呈報格式

輸出以下內容，供使用者審閱後授權 commit/push/PR：

---

## H2 PR 發布核准 — `<branch name>`

### 變更摘要

**Commits：**
```
<git log 輸出>
```

**變更檔案：**
```
<git diff --stat 輸出>
```

### 設計理由

<由 Implementer 在呼叫本 skill 前補充，或呼叫後手動填入>

### 本機檢查結果

| 檢查項目 | 結果 |
|----------|------|
| Spotless 格式 | ✅ 通過 / ⚠️ 已修正 N 個檔案 |
| 測試 | ✅ N/N 通過 / ❌ N 失敗（詳見下方） |
| Secret 掃描 | ✅ 無敏感資料 |

<若測試失敗，列出細節>

### 風險

<Implementer 填寫：授權、Prompt Injection、工具誤用、模型/Provider 故障、敏感資料等風險導向評估>

### 請求核准

請審閱以上 Diff 與結果，授權首次 Commit、Push 並建立 PR。  
H2 授權涵蓋此次 SPEC 範圍內後續的 QA 修正提交，不必逐輪核准。

---

## 注意事項

- Spotless 修正的格式變更須一併納入即將 commit 的內容，不要分開 commit
- 測試若有 `@Disabled` 略過項目，必須在 H2 格式中說明原因
- Secret 掃描是人工輔助，不能保證 100% 完整；Implementer 仍須親自審閱 diff 中的敏感欄位
- 本 checklist 完成後，等使用者明確授權才執行 `git commit` / `git push` / `gh pr create`
