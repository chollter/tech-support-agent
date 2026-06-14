# OpsMind（tech-support-agent）架构重构方案

> 版本：v1.0  
> 日期：2026-06-12  
> 状态：Phase 1–3 已完成  
> 关联：`AGENTS.md`（已同步 §4 目标流程与五层架构）、`README.md`（已同步核心流程）

---

## 1. 文档目的

本文档将前期讨论的 **「五层 Plan-and-Execute 混合架构」** 落地为可执行的重构清单，明确：

- 为什么要改（现状瓶颈）
- 改到什么形态（目标架构与流程）
- 改哪些文件（**删除 / 重写 / 改名 / 保留 / 新增**）
- 分几期做、如何验收

**不在本文档范围**：前端大改、后台管理系统、与 Incident 处置无关的技术堆叠。

---

## 2. 现状问题（重构动机）

当前项目是 **Pipeline Agent**：流程固定、规则硬编码占比高，LLM 仅用于抽取、根因、建议等少数步骤。

| 问题 | 现状代码 | 后果 |
|------|----------|------|
| 完整度只有 Schema 维度 | `InfoCompletenessChecker` 固定 6 个 slot | 字段齐了但语义仍不够时误进入分析 |
| 追问只有模板 lookup | `MissingFieldQuestionService` 6 条固定 map | 超出 6 类场景无法追问 |
| 非 INCIDENT 直接放行 | `issueType != INCIDENT → complete=true` | CONSULT/DATA 等也可能需要追问 |
| 执行链写死 | `AgentRuntime.doExecute` 顺序固定 | 无法按场景跳过/优先 RAG 或 Tool |
| Tool 全量执行 | `EvidenceCollectionService` 遍历所有 Tool | 浪费、无关 tool 噪声 |
| 路由/优先级纯规则 | `TeamRoutingService` / `PriorityEvaluationService` | 新系统名、边界 case 失效 |
| Prompt 半成品 | `follow-up-polish.txt` 存在但未接入 | 设计与实现脱节 |
| Mock 与真实路径混杂 | `MockLogMcpServer` / `MockMetricMcpServer` | 简历/demo 表达不清晰 |

**核心结论**：不是改成「全自动 ReAct Agent」，而是 **「治理层硬规则 + 理解/规划层 LLM 有限自主 + 执行层工具白名单」**。

---

## 3. 目标架构：五层分工

```text
┌─────────────────────────────────────────────────────────────┐
│ L0 接入与治理层（Java，不调 LLM）                              │
│   输入装配、截断、幂等、审计、会话态                            │
├─────────────────────────────────────────────────────────────┤
│ L1 理解层（LLM 主责 + 规则降级）                               │
│   结构化抽取 TicketExtractResult                             │
│   语义缺口分析 InfoGapAnalysis（新增）                         │
├─────────────────────────────────────────────────────────────┤
│ L2 决策层（规则 + LLM 混合）                                  │
│   完整度合并 CompletenessDecision                            │
│   追问生成 FollowUpQuestionService（模板 + LLM）              │
│   步骤规划 AgentPlanner（受限枚举，新增）                      │
├─────────────────────────────────────────────────────────────┤
│ L3 执行层（LLM 建议 + Java 执行）                             │
│   RAG 检索、ToolSelector 选工具、Evidence 收集                │
├─────────────────────────────────────────────────────────────┤
│ L4 结论层（LLM 推理 + 规则兜底）                              │
│   根因分析、Runbook / 建议生成                                │
├─────────────────────────────────────────────────────────────┤
│ L5 治理输出层（硬规则，LLM 不可 override）                     │
│   优先级、默认路由、Human-in-the-loop                         │
└─────────────────────────────────────────────────────────────┘
```

### 3.1 各层任务边界

| 层级 | 做什么 | 不做什么 |
|------|--------|----------|
| L0 | 装配 `AgentContext`、幂等、锁、审计 | 语义理解 |
| L1 | NLU → 结构化对象 + 语义缺口 JSON | 排障结论、分派决定 |
| L2 | 决定是否追问、问什么、下一步动作枚举 | 调用外部系统 |
| L3 | 按 Planner 执行 RAG/Tool，写 ToolExecutionLog | 编造日志/指标 |
| L4 | 基于 Evidence 生成 hypothesis / Runbook | 最终 P0/P1 裁决 |
| L5 | Priority / Routing baseline / HITL | 被 LLM 绕过 |

---

## 4. 目标流程与状态机

### 4.1 主流程（替换 README / AGENTS.md 中的旧流程描述）

```text
SUBMITTED
  -> PREPROCESS
  -> TICKET_EXTRACT                    [L1]
  -> INFO_GAP_ANALYSIS                 [L1 新增]
  -> COMPLETENESS_DECISION             [L2 新增，合并 schema + semantic + policy]
  -> [不足] FOLLOW_UP_QUESTION_GENERATE [L2 重写]
  -> WAIT_USER_INPUT
  -> [足够] AGENT_PLAN                 [L2 新增]
  -> KNOWLEDGE_SEARCH                  [L3，可按 Plan 跳过]
  -> TOOL_SELECTION                    [L3 新增]
  -> EVIDENCE_COLLECTION               [L3 重写：只跑选中 tool]
  -> ROOT_CAUSE_ANALYSIS               [L4]
  -> PRIORITY_EVALUATION               [L5]
  -> TEAM_ROUTING                      [L5 + LLM suggestion 合并]
  -> SUGGESTION_GENERATION             [L4]
  -> [高风险/低置信度] WAIT_HUMAN_CONFIRM [L5]
  -> FINAL
```

### 4.2 `AgentStepName` 变更

**文件**：`agent/AgentStepName.java`

| 动作 | 枚举值 | 说明 |
|------|--------|------|
| 保留 | `SUBMITTED`, `PREPROCESS`, `TICKET_EXTRACT`, `KNOWLEDGE_SEARCH`, `EVIDENCE_COLLECTION`, `ROOT_CAUSE_ANALYSIS`, `PRIORITY_EVALUATION`, `TEAM_ROUTING`, `SUGGESTION_GENERATION`, `WAIT_HUMAN_CONFIRM`, `FINAL` | — |
| **改名** | `INFO_COMPLETENESS_CHECK` → `COMPLETENESS_DECISION` | 语义从「查表」变为「合并决策」 |
| **新增** | `INFO_GAP_ANALYSIS` | L1 语义缺口 |
| **新增** | `AGENT_PLAN` | L2 步骤规划 |
| **新增** | `TOOL_SELECTION` | L3 工具选择 |
| 保留名 | `FOLLOW_UP_QUESTION_GENERATE` | 实现重写 |

---

## 5. 包结构重组

### 5.1 目标目录（`com.gcll.ticketagent`）

```text
agent/
  AgentRuntime.java              # 重写：编排 L1-L5，不再塞满业务逻辑
  AgentStepName.java             # 扩展枚举
  TicketAgentOrchestrator.java   # 保留，薄封装
  AgentStepEventPublisher.java   # 保留
  context/
    AgentContext.java            # 新增：单次 run 上下文载体
    AgentPlan.java               # 新增：Planner 输出
  planner/
    AgentPlanner.java            # 新增接口
    SpringAiAgentPlanner.java    # 新增
    RuleBasedAgentPlanner.java   # 新增降级

understanding/                   # 新增包（从 extract/completeness 拆出 L1/L2 理解职责）
  gap/
    InfoGapAnalysis.java         # 新增 record
    InfoGapAnalysisService.java  # 新增接口
    SpringAiInfoGapAnalysisService.java
    RuleBasedInfoGapAnalysisService.java
  completeness/
    CompletenessDecision.java    # 新增 record（替代仅用 CompletenessResult 决策）
    CompletenessDecisionService.java  # 新增：合并 schema + gap + policy
  followup/
    FollowUpQuestionService.java      # 重写（合并原 MissingFieldQuestionService）
    TemplateFollowUpProvider.java       # 新增：原 6 类模板
    LlmFollowUpProvider.java            # 新增：LLM 场景化追问

extract/                         # 保留，职责收窄为 L1a 结构化抽取
completeness/                    # 逐步废弃，见 §6 删除清单

planning/                        # 可选：若 planner 类过多可独立，否则放 agent/planner

execution/                       # 新增包（L3）
  tool/
    ToolSelector.java            # 新增接口
    SpringAiToolSelector.java
    RuleBasedToolSelector.java
  evidence/
    EvidenceCollectionService.java   # 从 tool 包移入并重写

governance/                      # 新增包（L5 显式化）
  priority/
    PriorityEvaluationService.java   # 从 priority/ 移入，逻辑保留
    PriorityPolicy.java              # 新增：规则表外置配置化（可选 Phase 3）
  routing/
    TeamRoutingService.java          # 从 routing/ 移入
    RoutingPolicyEngine.java         # 新增：规则 + LLM suggestion merge
    RoutingSuggestion.java           # 新增 record
  human/
    HumanConfirmTrigger.java         # 从 human/ 移入
    HumanConfirmService.java

# 以下包基本保留原位
ticket/ audit/ async/ api/ config/ domain/ eval/ human/ infra/
knowledge/ llm/ persistence/ analysis/ suggestion/ tool/function/ tool/mcp/
metrics/
```

### 5.2 包迁移原则

- **理解 vs 治理分离**：`understanding/` 负责「听懂、问清」；`governance/` 负责「裁决、分派、HITL」。
- **编排 vs 能力分离**：`AgentRuntime` 只编排；具体能力在各 Service。
- **LLM 实现统一走** `LlmGateway` + `resources/prompts/`，禁止 Java 硬编码大段 Prompt。

---

## 6. 文件级变更清单

图例：**🗑 删除** · **✏️ 重写** · **📛 改名/移动** · **✅ 保留** · **🆕 新增**

### 6.1 Agent 编排

| 文件 | 动作 | 说明 |
|------|------|------|
| `agent/AgentRuntime.java` | ✏️ 重写 | 接入 gap/plan/toolSelection；步骤审计对齐新枚举 |
| `agent/AgentStepName.java` | ✏️ 重写 | 见 §4.2 |
| `agent/TicketAgentOrchestrator.java` | ✅ 保留 | — |
| `agent/AgentStepEventPublisher.java` | ✏️ 小改 | 新步骤 SSE 事件 |
| `agent/context/AgentContext.java` | 🆕 | runId、draft、extract、gap、plan、evidence 聚合 |
| `agent/context/AgentPlan.java` | 🆕 | `nextActions[]`、`skipActions[]`、`reason` |
| `agent/planner/AgentPlanner.java` | 🆕 | 接口 |
| `agent/planner/SpringAiAgentPlanner.java` | 🆕 | Prompt `agent-plan.txt` |
| `agent/planner/RuleBasedAgentPlanner.java` | 🆕 | 默认全量顺序降级 |

### 6.2 理解层（L1/L2）

| 文件 | 动作 | 说明 |
|------|------|------|
| `extract/TicketExtractService.java` | ✅ 保留 | 接口不变 |
| `extract/TicketExtractResult.java` | ✅ 保留 | 可选 Phase 3 增加 `rawSummary` |
| `extract/SpringAiTicketExtractService.java` | ✏️ 小改 | 增强降级日志；禁止改接口签名 |
| `extract/RuleBasedTicketExtractService.java` | ✏️ 小改 | 保留为降级；**不再无限膨胀关键词**（见 §8.1） |
| `completeness/InfoCompletenessChecker.java` | 📛→✅ | 迁至 `understanding/completeness/SchemaCompletenessChecker.java`，职责收窄为 schema 检查 |
| `completeness/CompletenessResult.java` | 📛 | 迁至 `understanding/completeness/SchemaCompletenessResult.java` |
| `completeness/MissingFieldQuestionService.java` | 🗑 | 由 `FollowUpQuestionService` 替代 |
| `understanding/gap/InfoGapAnalysis*.java` | 🆕 | 见 §7.1 |
| `understanding/completeness/CompletenessDecision*.java` | 🆕 | 合并 schema + gap + policy |
| `understanding/followup/FollowUpQuestionService.java` | 🆕 | 模板 + LLM 合并去重，最多 6 条 |

### 6.3 执行层（L3）

| 文件 | 动作 | 说明 |
|------|------|------|
| `evidence/EvidenceCollectionService.java` | 📛→✏️ | 迁至 `execution/evidence/`，只执行 `ToolSelector` 选中项 |
| `tool/ToolGateway.java` | ✏️ 小改 | 增加 `supports(TicketExtractResult, AgentPlan)` 或 `ToolDescriptor` |
| `tool/ToolDescriptor.java` | 🆕 | 名称、类型、适用场景描述（给 Selector 用） |
| `execution/tool/ToolSelector*.java` | 🆕 | Prompt `tool-select.txt` |
| `tool/function/SimilarCaseFunctionTool.java` | ✅ 保留 | 补充 `ToolDescriptor` |
| `tool/mcp/DatabaseLogMcpTool.java` | ✅ 保留 | 生产路径 |
| `tool/mcp/DatabaseMetricMcpTool.java` | ✅ 保留 | 生产路径 |
| `tool/mcp/MockLogMcpServer.java` | 🗑 | 删除；测试改用 `application-test.yml` stub 或 Testcontainers |
| `tool/mcp/MockMetricMcpServer.java` | 🗑 | 同上 |

### 6.4 结论层（L4）

| 文件 | 动作 | 说明 |
|------|------|------|
| `analysis/RootCauseAnalysisService.java` | ✅ 保留 | 输入增加 `AgentContext` 或保持现有参数 |
| `analysis/LlmRootCauseAnalysisService.java` | ✏️ 小改 | Prompt 上下文带上 gap/plan 摘要 |
| `analysis/RuleBasedRootCauseAnalysisService.java` | ✅ 保留 | 降级 |
| `suggestion/SuggestionGenerationService.java` | ✅ 保留 | — |
| `suggestion/SpringAiSuggestionGenerationService.java` | ✏️ 小改 | 同上 |
| `suggestion/TemplateSuggestionGenerationService.java` | ✅ 保留 | 降级 |

### 6.5 治理层（L5）

| 文件 | 动作 | 说明 |
|------|------|------|
| `priority/PriorityEvaluationService.java` | 📛 | 迁至 `governance/priority/`，逻辑 Phase 1 **保留** |
| `routing/TeamRoutingService.java` | 📛→✏️ | 迁至 `governance/routing/`；Phase 3 加 `RoutingPolicyEngine` |
| `governance/routing/RoutingPolicyEngine.java` | 🆕 Phase 3 | `merge(ruleResult, llmSuggestion)`，冲突时规则优先 |
| `governance/routing/SpringAiRoutingSuggestionService.java` | 🆕 Phase 3 | Prompt `routing-suggest.txt` |
| `human/HumanConfirmTrigger.java` | 📛 | 迁至 `governance/human/` |
| `human/HumanConfirmService.java` | 📛 | 迁至 `governance/human/` |

### 6.6 接入层 / 基础设施（L0）

| 文件 | 动作 | 说明 |
|------|------|------|
| `ticket/TicketApplicationService.java` | ✏️ 小改 | supplement 时持久化上一轮 `InfoGapAnalysis`（可选 JSON 字段） |
| `ticket/TicketDraft.java` | ✅ 保留 | — |
| `infra/RunConcurrencyService.java` | ✅ 保留 | — |
| `audit/AuditLogService.java` | ✏️ 小改 | 新步骤名、可存 gap/plan 摘要 |
| `async/*` | ✅ 保留 | 异步 run 同样走新 `AgentRuntime` |

### 6.7 LLM 与 Prompt

| 文件 | 动作 | 说明 |
|------|------|------|
| `llm/LlmGateway.java` | ✅ 保留 | 确保 Connect/Read Timeout |
| `llm/StructuredOutputParser.java` | ✅ 保留 | — |
| `prompts/ticket-extract.txt` | ✏️ 小改 | 补充「勿猜测、未知填 null」 |
| `prompts/follow-up-polish.txt` | 🗑 | 删除，由 `follow-up-generate.txt` 替代 |
| `prompts/follow-up-generate.txt` | 🆕 | 模板问题 + gap + 原文 → 场景化追问 JSON |
| `prompts/info-gap-analysis.txt` | 🆕 | 语义缺口分析 |
| `prompts/agent-plan.txt` | 🆕 | 受限动作枚举 |
| `prompts/tool-select.txt` | 🆕 | 工具白名单选择 |
| `prompts/routing-suggest.txt` | 🆕 Phase 3 | 路由建议，非最终裁决 |
| `prompts/root-cause-analysis.txt` | ✏️ 小改 | 强调 evidence 引用 |
| `prompts/suggestion-generate.txt` | ✅ 保留 | — |
| `prompts/system-base.txt` | ✏️ 小改 | 统一安全约束：禁止 Prompt Injection |

### 6.8 测试与 Eval

| 文件 | 动作 | 说明 |
|------|------|------|
| `test/.../IncidentAnalysisFlowTest.java` | ✏️ 重写 | 覆盖 gap 追问、plan 跳步 |
| `test/.../InfoCompletenessCheckerTest.java` | 📛→✏️ | 改为 `CompletenessDecisionServiceTest` |
| `test/.../EvidenceCollectionServiceTest.java` | ✏️ 重写 | mock ToolSelector |
| `eval/EvalRunner.java` | ✏️ 重写 | 新增 golden case，见 §9 |
| `eval/EvalCase.java` | ✏️ 小改 | 增加 `expectGapAnalysis`、`expectPlanContains` 等 |

### 6.9 文档

| 文件 | 动作 | 说明 |
|------|------|------|
| `README.md` | ✏️ | 更新核心流程图与新接口说明 |
| `AGENTS.md` | ✏️ | 同步 §6 目标流程、LLM 边界 |
| `docs/REFACTORING-PLAN.md` | ✅ | 本文档 |
| `docs/ARCHITECTURE.md` | 🆕 Phase 2 | 五层架构详图 + 面试话术 |

---

## 7. 核心新增模型与 Prompt 契约

### 7.1 `InfoGapAnalysis`（L1 输出）

```java
public record InfoGapAnalysis(
    List<String> schemaMissing,       // 与 SchemaCompletenessChecker 对齐的 key
    List<String> semanticGaps,        // 自由文本描述的缺口
    List<String> suggestedQuestions,  // LLM 建议的追问（尚未合并模板）
    String blockingReason,
    boolean readyForAnalysis,
    double confidence,
    boolean llmUsed
) {}
```

**Prompt**：`resources/prompts/info-gap-analysis.txt`

**输入变量**（由 Java 替换，用户原文仅作为 user content）：

- `{extractJson}`：`TicketExtractResult` 序列化
- `{schemaPolicy}`：INCIDENT 必填字段说明（静态配置）
- `{userContent}`：截断后的用户原文

**输出 JSON**：

```json
{
  "schemaMissing": ["environment", "timeRange"],
  "semanticGaps": ["未说明偶发还是必现", "未说明读写接口类型"],
  "suggestedQuestions": ["问题是必现还是偶发？", "影响的是查询还是下单接口？"],
  "blockingReason": "无法判断影响面",
  "readyForAnalysis": false,
  "confidence": 0.35
}
```

### 7.2 `CompletenessDecision`（L2 输出）

```java
public record CompletenessDecision(
    boolean canProceed,
    boolean needFollowUp,
    List<String> missingSchemaFields,
    List<String> semanticGaps,
    List<String> followUpQuestions,   // 最终返回用户的追问
    String decisionReason
) {}
```

**合并规则（Java，非 LLM）**：

```text
needFollowUp =
    !SchemaComplete
    OR !gap.readyForAnalysis
    OR PolicyBlock（如 INCIDENT 缺 environment）

canProceed = !needFollowUp
```

**追问合并**：

1. `TemplateFollowUpProvider` 根据 `missingSchemaFields` 生成保底问题  
2. `LlmFollowUpProvider` 使用 `follow-up-generate.txt` 润色/扩展  
3. 去重、上限 6 条、禁止排障结论  

### 7.3 `AgentPlan`（L2 输出）

```java
public record AgentPlan(
    List<AgentAction> actions,   // 有序
    List<AgentAction> skipped,
    String reason,
    boolean llmUsed
) {}

public enum AgentAction {
    KNOWLEDGE_SEARCH,
    SIMILAR_CASE_SEARCH,
    QUERY_LOGS,
    QUERY_METRIC
}
```

Planner **只能**从枚举中选择；Java 校验非法 action 时回退 `RuleBasedAgentPlanner`。

### 7.4 `ToolSelection`（L3 输出）

```java
public record ToolSelection(
    List<String> selectedToolNames,
    Map<String, String> parameters,
    String reason,
    boolean llmUsed
) {}
```

---

## 8. 规则与 LLM 边界（重构后仍遵守）

### 8.1 `RuleBasedTicketExtractService` 收敛策略

**问题**：当前规则类无限膨胀关键词（`RuleBasedTicketExtractService` 200+ 行 if-else）。

**重构策略**：

- Phase 1：**保留**为 LLM 不可用时的 Demo 降级，但 **冻结**不再新增关键词。
- Phase 2：规则只覆盖 **Eval 的 10 个 golden case** 最低字段。
- 新场景依赖 LLM extract + gap analysis，不回退到加 if-else。

### 8.2 治理层不可被 LLM override

| 决策 | 最终裁决者 | LLM 角色 |
|------|------------|----------|
| P0/P1/P2/P3 | `PriorityEvaluationService` | 无 |
| 是否 HITL | `HumanConfirmTrigger` | 无 |
| 默认责任团队 | `TeamRoutingService` 规则 | Phase 3：`RoutingSuggestion` 仅供参考 |
| 是否进入分析 | `CompletenessDecisionService` | `InfoGapAnalysis` 提供信号 |

---

## 9. Eval Golden Case 扩展

在现有 `inc_001` ~ `inc_010` 基础上 **新增**：

| ID | 场景 | 期望 |
|----|------|------|
| `inc_011` | 「结算批处理有时跑不完」 | `NEED_MORE_INFO`；追问含 **非 6 类模板** 语义问题（Job 名/是否影响结算窗口） |
| `inc_012` | 字段齐但语义模糊：「生产支付接口偶尔慢」 | `NEED_MORE_INFO`；gap 识别偶发/读写类型 |
| `inc_013` | OOM 完整描述 | `AgentPlan` 含 `QUERY_METRIC`，可跳过 `SIMILAR_CASE` |
| `inc_014` | 咨询类：「如何配置 MFA」 | `issueType=CONSULT`；可走简化路径或专用 plan |

**EvalCase 新字段建议**：

```java
boolean expectSemanticFollowUp;
List<String> questionKeywords;   // 追问中至少命中其一
List<String> expectPlanActions;
```

---

## 10. 数据库与 API 变更（可选）

### 10.1 表结构（Phase 2，可选）

`agent_run` 表增加：

```sql
gap_analysis_json   TEXT NULL,
agent_plan_json     TEXT NULL,
tool_selection_json TEXT NULL
```

用于多轮 `supplement` 时带上上一轮 gap，避免重复追问。

### 10.2 API

**默认不变**：`POST /api/tickets/agent-runs`、`supplement`、SSE、audit。

**可选增强**（Phase 2）：

- `AgentRunResponse` 增加 `gapAnalysisSummary`、`plannedSteps`（便于前端展示 Agent 思考链）
- DTO 保持 `isAiGenerated: true` 标识（遵守 AI 安全规则）

---

## 11. 分阶段实施计划

### Phase 1 — 解决「追问失灵」（**已完成**）

- [x] 新增 `understanding/gap/*`、`InfoGapAnalysisService`
- [x] 新增 `CompletenessDecisionService`、`FollowUpQuestionService`
- [x] 新增 Prompt：`info-gap-analysis.txt`、`follow-up-generate.txt`
- [x] 删除 `MissingFieldQuestionService`、`follow-up-polish.txt`
- [x] 重写 `AgentRuntime` 接入 gap + 新追问
- [x] 更新 `AgentStepName`、审计、测试、`inc_011`/`inc_012` Eval
- [x] 更新 `README.md` / `AGENTS.md` 流程说明

**Phase 1 不改**：Priority、Routing、Tool 全量执行、Mock 删除

### Phase 2 — Planner + ToolSelector（**已完成**）

- [x] 新增 `AgentPlanner`、`ToolSelector` 及降级实现
- [x] 重写 `EvidenceCollectionService`（按选中 tool 执行）
- [x] `ToolGateway` + `ToolRegistry` + `ToolDescriptor`
- [x] 删除 `MockLogMcpServer`、`MockMetricMcpServer`
- [x] Eval 增加 `inc_013`；补 `docs/ARCHITECTURE.md`

### Phase 3 — 治理层增强 + 包迁移（**已完成**）

- [x] 包迁移：`governance/`（priority、routing、human 治理类）
- [x] `RoutingPolicyEngine` + `routing-suggest.txt`
- [x] 收敛 `RuleBasedTicketExtractService`（冻结 Eval 覆盖范围）
- [x] `agent_run` 增加 gap/plan/tool JSON 持久化
- [x] Eval 扩展 `inc_014`；`evidence/` → `execution/evidence/`

---

## 12. 验收标准

| 类别 | 标准 |
|------|------|
| 功能 | 「接口报错了」仍返回 NEED_MORE_INFO；「结算批处理有时跑不完」返回 **语义化** 追问 |
| 降级 | 无 `LLM_API_KEY` 时 Phase 1 仍可用模板追问；核心 Demo 不挂 |
| 安全 | Prompt 全在 `resources/prompts/`；日志不打印完整 Prompt/Response |
| 审计 | 新步骤均有 `AgentStep` 记录；SSE 可推送 |
| 测试 | `mvn test` 全绿；`POST /api/evals/run` golden case ≥ 12 条通过率 100% |
| 工程 | 无循环查库；LLM 调用有 timeout；失败有降级 |

---

## 13. 明确不做的事

- ❌ 单一 mega prompt 包办全流程  
- ❌ 无白名单的 ReAct 无限 loop  
- ❌ LLM 直接决定 P0 分派、跳过 HITL  
- ❌ 在 `RuleBasedTicketExtractService` 继续堆关键词作为长期方案  
- ❌ 删除 `PriorityEvaluationService` / `HumanConfirmTrigger` 规则体系  

---

## 14. 实施顺序速查（给开发者的 Checklist）

```text
1. 新建 understanding/gap + prompts/info-gap-analysis.txt
2. 新建 CompletenessDecisionService + FollowUpQuestionService
3. 改 AgentStepName + AgentRuntime（PREPROCESS → EXTRACT → GAP → DECISION → FOLLOW_UP）
4. 删 MissingFieldQuestionService、follow-up-polish.txt
5. 改测试 + Eval inc_011/012 + README
--- Phase 1 完成，可演示 ---
6. 新建 AgentPlanner + ToolSelector + 对应 prompts
7. 改 EvidenceCollectionService；删 Mock MCP Server
--- Phase 2 完成 ---
8. 包迁移 governance/ execution/；RoutingPolicyEngine
9. 全文档 + 全 Eval 回归
--- Phase 3 完成 ---
```

---

## 15. 参考：重构前后对比

| 维度 | 重构前 | 重构后 |
|------|--------|--------|
| 完整度 | 6 字段 schema | schema + semantic gap |
| 追问 | 固定 6 模板 | 模板保底 + LLM 场景化 |
| 执行链 | 写死顺序 | Planner 受限枚举 |
| Tool | 全量跑 | Selector 白名单 |
| 路由/优先级 | 纯 if-else | 规则裁决 + LLM 建议（可选） |
| 自主性 | 低 | 中（Plan-and-Execute） |
| 可控性 | 高 | 仍高（L5 治理层） |

---

**维护说明**：实施过程中每完成一个 Phase，在本文件对应章节打勾，并同步更新 `AGENTS.md` §4 目标状态机与 §12.2 Roadmap 状态表。
