---
name: qa-handoff
description: 給定 PR 號碼，自動蒐集 SPEC、diff、CI 狀態，產出標準 QA session 開場 brief，供開啟新 QA 對話時貼入。
---

# QA Handoff Bootstrap

## 用途

本 skill 供 Implementer 在 H2 建立 PR 後使用。輸入 PR 號碼，自動蒐集所有 QA 需要的材料，輸出一份可直接貼入新對話的 QA brief。

## 執行步驟

### 1. 讀取輸入

從呼叫參數取得 `<PR>` 號碼（例如 `2`）。若未提供，詢問使用者。

### 2. 蒐集材料（並行執行）

```powershell
# PR 基本資訊 + description
gh pr view <PR> --repo <owner>/<repo> --json number,title,headRefName,body,baseRefName

# CI 狀態
gh pr checks <PR> --repo <owner>/<repo>

# PR diff（只取前 500 行以節省 token；完整版 reviewer 自行跑）
gh pr diff <PR> --repo <owner>/<repo>
```

從 `git remote get-url origin` 推導 `<owner>/<repo>`。

### 3. 讀取 SPEC

在 repo 根目錄找 `SPEC-*.md`（若有多個，優先選與 PR branch 名稱相符的，例如 `feature/f02-*` → `SPEC-F02.md`）。

讀取全文，提取：
- **範圍（Scope）**
- **BDD 驗收條件**（所有 Given/When/Then）
- **錯誤行為與限制**
- **§ 安全 / 資料保護 / 稽核**（若有）

### 4. 組出 QA Brief

輸出以下格式，供直接貼入新 QA 對話的第一則訊息：

---

```
# QA Review — <PR title>（PR #<N>）

## 角色

你是獨立 QA Reviewer，依核准的 SPEC 驗收此 PR。  
**不沿用實作對話的任何判斷**，以 SPEC + diff + CI 為唯一依據。

## 材料

| 項目 | 內容 |
|------|------|
| SPEC | `<SPEC 檔名>`（已附於下方） |
| PR   | <PR URL> |
| Branch | `<headRefName>` → `<baseRefName>` |
| CI   | <pass/fail/pending — 列出每個 check 狀態> |

## SPEC 重點摘錄

### 範圍
<從 SPEC 提取>

### BDD 驗收條件
<列出所有 Given/When/Then>

### 錯誤行為與限制
<從 SPEC 提取>

## PR Diff 摘要

變更檔案：
<列出 diff 中的 +++ 檔名>

關鍵變更（前 300 行節錄）：
<diff 節錄>

## QA 任務

依 AGENTS.md 規範回報發現：

| 等級 | 標準 |
|------|------|
| Critical | 資安漏洞、資料遺失、系統崩潰 |
| Major | 違反 SPEC 驗收條件、功能錯誤 |
| Minor | 程式碼品質、非 SPEC 要求的改進 |

每項發現須包含：
- 失敗條件與重現方式
- 相關檔案與行號
- 證據（測試輸出、diff 行、SPEC 章節）
- 影響與建議

**能對應 diff 行的發現必須以 inline PR comment 錨定。**  
若無 GitHub 寫入權限，輸出格式為：

```
File: <path>
Line: <N>
Body: [QA Reviewer] <發現內容>
```

由實作對話的 Claude Code 代貼，標明「Reviewer: QA session / Posted by: Implementer session」。
```

---

### 5. 附加提醒（輸出在 brief 之後）

向使用者說明：
1. 複製上方 brief，開啟**全新** Claude Code 對話（不繼承本對話上下文）
2. 貼入後 QA session 即可開始工作
3. QA 完成後，回到本對話繼續 H3 流程

## 注意事項

- diff 若超過 300 行，僅節錄；告知 QA reviewer 可用 `gh pr diff <PR>` 取得完整版
- CI pending 時說明，建議等 CI 完成再開 QA（除非使用者要求提前）
- 不代替 QA 判斷任何發現；本 skill 只產出材料，不預判結果
