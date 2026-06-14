# OpsMind 架构说明

> 关联：[AGENTS.md](../AGENTS.md) · [REFACTORING-PLAN.md](./REFACTORING-PLAN.md)

## 1. 设计形态

**Plan-and-Execute 混合 Agent**：LLM 在受控步骤内规划与选工具，Java 状态机编排，Policy 规则裁决 P0/HITL/路由。

```text
LLM 负责听懂和规划，Policy Engine 负责裁决，Java 负责执行和审计。
```

## 2. 五层架构

| 层 | 包 | 职责 |
|----|-----|------|
| L0 | `ticket/` `infra/` `audit/` | 接入、幂等、审计 |
| L1 | `extract/` `understanding/gap/` | 结构化抽取、语义缺口 |
| L2 | `understanding/completeness/` `understanding/followup/` `agent/planner/` | 完整度决策、追问、AgentPlan |
| L3 | `knowledge/` `execution/tool/` `execution/evidence/` | RAG、Tool 选择、取证 |
| L4 | `analysis/` `suggestion/` | 根因、Runbook |
| L5 | `governance/priority/` `governance/routing/` `governance/human/` | 优先级、路由、HITL |

## 3. 状态机（当前实现）

```text
SUBMITTED → PREPROCESS → TICKET_EXTRACT
→ INFO_GAP_ANALYSIS → COMPLETENESS_DECISION
→ [不足] FOLLOW_UP → WAIT_USER_INPUT
→ [足够] AGENT_PLAN
→ KNOWLEDGE_SEARCH（可按 Plan 跳过）
→ TOOL_SELECTION → EVIDENCE_COLLECTION（仅选中 Tool）
→ ROOT_CAUSE → PRIORITY → ROUTING（规则 + LLM 建议 merge）→ SUGGESTION
→ [HITL] → FINAL
```

## 4. L2/L3 关键组件

### AgentPlanner

- **接口**：`agent/planner/AgentPlanner`
- **输出**：`AgentPlan(actions, skipped, reason)`
- **枚举**：`KNOWLEDGE_SEARCH` | `SIMILAR_CASE_SEARCH` | `QUERY_LOGS` | `QUERY_METRIC`
- **规则降级**：OOM → 优先 metric/logs，跳过 similar case；连接池 → 优先 logs

### ToolSelector

- **接口**：`execution/tool/ToolSelector`
- **输出**：`ToolSelection(selectedToolNames, parameters, reason)`
- **白名单**：`searchSimilarCases` | `query_logs` | `query_metric`
- **注册表**：`tool/ToolRegistry` 按 `toolName()` 查找 `ToolGateway`

### EvidenceCollectionService

- 仅执行 `ToolSelection` 中选中的工具
- 每次调用写入 `tool_execution_log`

### RoutingPolicyEngine（L5）

- **规则基线**：`TeamRoutingService` 按系统/模块 if-else 裁决主责团队
- **LLM 建议**：`RoutingSuggestionService` + Prompt `routing-suggest.txt`（仅供参考）
- **合并**：`RoutingPolicyEngine.merge(ruleResult, suggestion)` — 冲突时规则优先
- **HITL**：低路由置信度（< 0.7）触发 `HumanConfirmTrigger`

## 5. 面试话术要点

1. **为什么不是 ReAct？** Incident 需要审计、HITL、Eval；步骤必须在枚举白名单内。
2. **追问怎么超出 6 类模板？** Schema 保底 + `InfoGapAnalysis` 语义缺口 + 模板/LLM 合并追问。
3. **Tool 为什么不全量跑？** `AgentPlan` 决定调查步骤，`ToolSelector` 映射到 Tool 白名单，`EvidenceCollectionService` 只跑选中项。
4. **OOM 场景？** Plan 跳过 `SIMILAR_CASE_SEARCH`，Selector 选 `query_metric` + `query_logs`（Eval `inc_013`）。
5. **路由为什么不全交给 LLM？** 规则 baseline 不可 override；LLM 仅输出 `RoutingSuggestion`，由 `RoutingPolicyEngine` 合并。

## 6. Golden Case

当前 **14** 条 Eval（`POST /api/evals/run`），覆盖信息不足、语义追问、OOM Plan、咨询类简化路径、支付/OOM/DB/权限等场景。
