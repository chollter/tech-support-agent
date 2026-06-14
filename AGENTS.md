# AGENTS.md — OpsMind 开发宪法

> **读者**：开发者、Cursor Agent、Code Review  
> **实施清单**：见 [docs/REFACTORING-PLAN.md](docs/REFACTORING-PLAN.md)  
> **用户文档**：见 [README.md](README.md)  
> **最后更新**：2026-06-12

本文件是 OpsMind 项目的统一开发规则。需求分析、代码修改、架构设计、测试补充、文档表达和 Demo 打磨，都要优先遵守本文。

---

## 1. 项目目标与业务定位

### 1.1 项目目标

项目核心目的不是做一个普通 Demo，而是支撑求职。

**目标岗位**：成都 Java 高级工程师 / AI 应用开发方向。

项目需要同时体现两类能力：

- **AI 应用能力**：Spring AI、RAG、Function Calling、MCP、Tool 调用、结构化输出、LLM 降级、Agent 编排、Human-in-the-loop、Eval。
- **工程化能力**：清晰架构、状态机设计、事务边界、幂等、并发控制、异常处理、可观测性、真实中间件、可测试、可部署、可恢复。

所有改造都要服务一个结果：**项目可以被写进简历，并且能在面试中经得起追问**。

### 1.2 业务定位

OpsMind 是企业级工单 / Incident 智能处置 Agent。

它要解决的不是「让 AI 聊天」，而是代替工单处理流程中重复、麻烦、低价值但又必须做的工作：

- 用户描述模糊时，引导补充必要信息，避免研发反复追问。
- 将自然语言问题整理成结构化工单。
- 自动识别系统、模块、接口、环境、错误码、发生时间和影响范围。
- 检索历史案例、SOP、错误码说明和相似故障。
- 调用真实工具查询日志、指标，形成证据链。
- 给出根因假设、证据链、优先级、责任团队和 Runbook。
- 对高风险事件进入人工确认，而不是让 AI 自动拍板。
- 记录完整审计轨迹，方便追责、复盘和持续优化。

**业务价值**：降低重复排障成本 · 提升工单信息质量 · 缩短 MTTR · 减少错派 · 提升新人处理效率 · 让 AI 输出可解释、可追踪、可复核。

### 1.3 功能取舍三问

每个新功能都要能回答：

1. 它解决了什么真实业务痛点？
2. 它体现了什么 AI 应用能力？
3. 它体现了什么高级工程师工程化能力？

【禁止】只为了堆技术名词而加模块。MCP、Function Call、RAG、Kafka、Redis、向量数据库都必须落在具体业务流程里。

---

## 2. Agent 设计哲学

### 2.1 形态定义

**Plan-and-Execute 混合 Agent**：

- 不是纯 Pipeline（LLM 只抽字段、其余全 if-else）。
- 不是无护栏 ReAct（LLM 自由循环调工具、自动分派）。

> **范式定位**：Plan-and-Execute 是 Workflow（确定性）与 Agent Loop（LLM 自主，如 Cursor / Claude Code）的折中——Plan 阶段给 LLM 调查路径与工具选择的自主权，Execute 阶段确定性执行。**卖点是「知道在哪用确定性、在哪给 LLM 自主权」，不是"分层"本身，更不是"提升自主性"（分层恰恰是提升可控性）。** 详见 [docs/PROJECT-OVERVIEW.md §5.1](docs/PROJECT-OVERVIEW.md)。

### 2.2 三层自主边界

| 负责方 | 做什么 |
|--------|--------|
| **LLM** | 理解、语义缺口发现、步骤建议、工具选择（白名单内）、根因与 Runbook 生成 |
| **Java** | 状态机编排、审计、幂等、事务、超时、降级、Tool 实际执行 |
| **规则（Policy）** | 优先级、Human-in-the-loop、默认责任团队 — **LLM 不可 override** |

**设计口号（面试可用）**：

```text
LLM 负责听懂和规划，Policy Engine 负责裁决，Java 负责执行和审计。
```

### 2.3 硬编码 vs LLM 自主

【必须】以下 deliberately 硬编码：P0/P1 判定、HITL 触发、幂等与事务、Tool 白名单、审计步骤。

【建议】以下增强 LLM 自主：语义缺口追问、AgentPlan 步骤选择、ToolSelector 工具选择、路由建议（规则 merge 后输出）。

【禁止】在 `RuleBasedTicketExtractService` 持续堆关键词作为长期方案；规则类仅作 LLM 不可用时的降级，新场景走 L1/L2 LLM 能力（见 REFACTORING-PLAN Phase 1–2）。

---

## 3. 五层架构与模块职责

`AgentRuntime` 是核心状态机所有者，**只编排，不堆业务 if-else**。

| 层 | 目标包 | 职责 | 调 LLM |
|----|--------|------|--------|
| **L0** 接入与治理 | `ticket/` `infra/` `audit/` `async/` | 输入装配、截断、幂等、锁、审计 | 否 |
| **L1** 理解 | `extract/` `understanding/gap/` | 结构化抽取、语义缺口分析 | 是 |
| **L2** 决策 | `understanding/completeness/` `understanding/followup/` `agent/planner/` | 完整度合并、追问生成、步骤规划 | 混合 |
| **L3** 执行 | `knowledge/` `execution/` `tool/` | RAG、Tool 选择、Evidence 收集 | 混合 |
| **L4** 结论 | `analysis/` `suggestion/` | 根因分析、Runbook / 建议生成 | 是 |
| **L5** 治理输出 | `governance/` `human/` | 优先级、路由 baseline、HITL | 规则为主 |

**当前代码与目标包映射**（重构进行中，见 REFACTORING-PLAN）：

| 现状 | 目标 |
|------|------|
| `completeness/` | → `understanding/completeness/` + `understanding/followup/` |
| `evidence/` | ✅ 已迁至 `execution/evidence/` |
| `priority/` `routing/` `human/`（治理类） | ✅ 已迁至 `governance/` |

【必须】保持：

- `TicketApplicationService` 管应用入口和短事务边界。
- `knowledge` 专注检索，不负责最终判断。
- `analysis` / `suggestion` 基于 Evidence，不编造来源。

【禁止】：

- Controller 直接写 Agent 编排逻辑。
- 一个 Service 包含抽取 + RAG + Tool + 路由 + 根因全部逻辑。
- LLM 结果未经结构化解析直接写入关键 DB 字段。
- 为了 Demo 在单方法里堆 if-else，导致架构无法讲解。

---

## 4. 目标状态机

### 4.1 主流程

```text
SUBMITTED
  -> PREPROCESS
  -> TICKET_EXTRACT                         [L1]
  -> INFO_GAP_ANALYSIS                      [L1]
  -> COMPLETENESS_DECISION                  [L2]
  -> [信息不足] FOLLOW_UP_QUESTION_GENERATE  [L2]
  -> WAIT_USER_INPUT
  -> [信息足够] AGENT_PLAN                   [L2]
  -> KNOWLEDGE_SEARCH                       [L3，可按 Plan 跳过]
  -> TOOL_SELECTION                         [L3]
  -> EVIDENCE_COLLECTION                    [L3，仅执行选中 Tool]
  -> ROOT_CAUSE_ANALYSIS                    [L4]
  -> PRIORITY_EVALUATION                    [L5]
  -> TEAM_ROUTING                           [L5 + 可选 LLM suggestion]
  -> SUGGESTION_GENERATION                  [L4]
  -> [高风险 / 低置信度] WAIT_HUMAN_CONFIRM   [L5]
  -> FINAL
```

> **说明**：`INFO_GAP_ANALYSIS` 至 `TOOL_SELECTION` **Phase 1–2 已落地**；**Phase 3** 治理层已迁移至 `governance/` 并接入 `RoutingPolicyEngine`。

### 4.2 流程不变量

【必须】遵守：

- 信息不足时，不能进入 RAG、取证、根因、优先级评估和建议生成。
- RAG 只提供知识证据，不能替代调查流程。
- Tool / MCP 结果必须进入证据链，根因与建议须引用或声明无来源。
- P0/P1、低置信度、涉及生产高风险动作时，必须 Human-in-the-loop。
- 每一步都要有审计记录（`AgentStep`）。

**面试 — 完整度门控**：

```text
Schema 完整度保底 SLA 必填字段；语义 Gap 覆盖模板外的追问；
两者由 Java CompletenessDecision 合并，避免 LLM 单独决定能否进入分析。
```

---

## 5. LLM 使用边界

LLM 用于自然语言理解和生成，**不用于安全敏感的最终裁决**。

### 5.1 允许 LLM（按步骤）

| 步骤 | 输出 |
|------|------|
| `TICKET_EXTRACT` | `TicketExtractResult` JSON |
| `INFO_GAP_ANALYSIS` | `InfoGapAnalysis` JSON（schemaMissing、semanticGaps、suggestedQuestions） |
| `FOLLOW_UP_QUESTION_GENERATE` | 追问列表 JSON（模板保底 + LLM 场景化扩展） |
| `AGENT_PLAN` | 受限 `AgentAction` 枚举列表 |
| `TOOL_SELECTION` | Tool 白名单内的名称与参数 |
| `ROOT_CAUSE_ANALYSIS` | hypothesis、evidence、unknowns、confidence |
| `SUGGESTION_GENERATION` | Runbook、actions、sources |
| RAG Query 改写 | 检索 query 字符串 |

### 5.2 禁止 LLM

【禁止】LLM 做以下事情：

- 最终决定优先级（P0–P3）。
- 最终决定责任团队（规则 baseline 不可被 override）。
- 编造日志、指标、命令输出或知识来源。
- 自动执行生产变更。
- 绕过人工确认。

【必须】LLM 输出结构化、可解析、可降级；关键步骤有规则 fallback（如 `RuleBasedTicketExtractService`）。

### 5.3 Prompt 与安全规范

【必须】：

- 提示词放在 `src/main/resources/prompts/`，禁止在 Java 中硬编码大段 Prompt。
- 用户原始输入作为 user content 传入，**禁止拼进 System Prompt**（防 Prompt Injection）。
- 对外部大模型的 HTTP 调用设置显式 Connect Timeout 和 Read Timeout（建议 Read ≥ 60s）。
- LLM / RAG / MCP 失败时有降级逻辑，不能把异常堆栈直接抛给前端。
- 日志只打印 traceId、Token 消耗、耗时；**禁止**打印完整 Prompt 和 Response。
- 返回前端的 AI 生成内容，DTO 中须有明确标识（如 `aiGenerated: true`）。
- RAG 查询前对用户输入做长度截断和敏感词过滤。

### 5.4 分发与通知边界

Agent **辅助决策，不自主执行**。分析结果通过两条路流转：

- **路 A（回提单人）**：低风险 / 咨询 / 信息不足 → 直接 API 响应 / SSE。
- **路 B（发责任团队）**：高风险 / 生产 Incident → **HITL 确认后**由通知 / 工单系统分发。

【必须】路 B 分发通过 **`NotificationService` / `DispatchService` 防腐层抽象**；核心流程禁止直接耦合 IM / ITSM 外部系统。当前用日志实现做演示，生产通过实现切换接入钉钉 / ServiceNow。

【禁止】Agent 自主向全员发告警、自主分派工单、自主执行生产变更——P0 / P1 必须经 `WAIT_HUMAN_CONFIRM`。

业务闭环全貌（数据来源 → Agent → 分发）见 [docs/PROJECT-OVERVIEW.md §4](docs/PROJECT-OVERVIEW.md)。

---

## 6. 工具、RAG、MCP

### 6.1 Function Call vs MCP

| 类型 | 适用场景 | 本项目示例 |
|------|----------|------------|
| **Function Call** | 应用内部能力 | `searchSimilarCases` |
| **MCP** | 跨系统外部工具 | `query_logs`、`query_metric` |

**面试表达**：

```text
Function Call 解决应用内部能力调用；MCP 解决跨工具、跨系统的标准化集成。
```

### 6.2 Tool 执行原则

【必须】Tool 由 **ToolSelector**（或 Planner）从白名单选择，禁止无脑遍历所有 Tool。

【必须】每次 Tool 调用写入 `tool_execution_log`，结果进入 Evidence 链。

【必须】主路径使用真实实现（如 `DatabaseLogMcpTool`、`DatabaseMetricMcpTool`）。

【禁止】把 Mock MCP 作为简历/Demo 核心卖点；Mock 仅用于单元测试或 profile 降级。

### 6.3 RAG 原则

RAG 是**调查步骤**，不是最终答案生成器。

【必须】：

- 检索结果有来源；命中有 `matchedReason`。
- 建议生成引用具体知识来源，或明确说明无来源。
- 知识来自真实业务样例：历史故障、SOP、错误码、Runbook、结案记录。

【禁止】无证据时编造「历史案例显示……」。

【建议】PostgreSQL 存元数据与正文；Milvus / pgvector 存向量；支持系统、模块、问题类型过滤。

### 6.4 真实环境要求

中间件优先真实环境：PostgreSQL、Redis、Kafka、Milvus / pgvector。

日志 / 指标使用可复现的样例数据（DB 种子），不要只写死在 Java 分支里。

---

## 7. 工程红线

### 7.1 事务与并发

【禁止】用一个大事务包住完整 Agent 流程。

【必须】短事务：创建 run、用户补充、审计 step、最终状态 + PendingAction、人工确认。

【禁止】LLM、RAG、MCP、向量检索、根因分析持有数据库事务。

【必须】同一 run 状态推进有锁或版本控制；提交 / 补充 / 异步消费有幂等。

### 7.2 可靠性

【必须】所有外部调用（LLM / 向量检索 / MCP 工具）经 `ExternalCallGateway` 统一治理；禁止在业务层手写重试 / 超时 / 熔断 / `future.get`。

【必须】外部调用超时分层生效：HTTP 层 socket readTimeout（DashScope `spring.ai.dashscope.read-timeout`）是底线，Resilience4j TimeLimiter 是应用级补充（其 cancel 对阻塞 IO 无效，靠 HTTP 层兜底）。

【必须】LLM、RAG、MCP、向量库失败时可解释降级（每个 SpringAi service 有 RuleBased fallback）。

【必须】只对可重试错误重试：执行器（如 `LlmGateway`）把底层异常翻译为 `RetryableCallException` / `NonRetryableCallException`；工具调用默认不重试（有副作用）。

【必须】日志、指标带 runId / traceId；外部调用统一经 `CallMetrics` 埋点 `external_call_duration` / `ai_token_usage`，禁止业务层手动埋 LLM 耗时。

### 7.3 代码规范（OpsMind 特有）

通用 Java 反模式见项目 `.cursor/rules`；此处为 Agent 项目特有约束：

【必须】：

- 方法职责单一；编排、规则、外部调用、持久化分层清楚。
- Service 层使用项目统一业务异常，禁止 `new RuntimeException()`。
- 禁止在循环里执行数据库查询（批量查后在内存处理）。
- MyBatis-Plus 单表 CRUD 用 Wrapper，禁止 `<if test>` 拼单表 SQL。
- 禁止擅自用 `Optional.ofNullable()` 替换简单 `!= null`（除非明确要求）。
- 禁止擅自把简单 for 循环改成复杂 Stream 链。
- 禁止在 Entity / POJO 里写 Lombok 以外的业务逻辑。
- 没有明确要求时，禁止擅自加 `@Async` 或 `Executors.newCachedThreadPool()`；线程池须在配置类显式定义 Bean。

【必须】新增能力有单元测试或 Eval 覆盖；不要吞异常且无审计。

【禁止】提交密钥；禁止在日志中记录用户敏感数据。

### 7.4 异步执行

Kafka 异步模式需考虑：消息幂等、消费重试、步骤状态机、死信、重启恢复、卡住任务扫描。

---

## 8. 数据与 API

### 8.1 核心表

| 表 | 用途 |
|----|------|
| `agent_run` | 一次 Agent 运行 |
| `agent_step` | 步骤审计 |
| `tool_execution_log` | Function / MCP 调用记录 |
| `knowledge_document` | 知识库 |
| `pending_action` | 人工确认 |

关键字段意图：status、traceId、idempotencyKey、costMs、confidence、errorMessage、createdAt / updatedAt。

【建议】Phase 2 在 `agent_run` 增加 `gap_analysis_json`、`agent_plan_json`、`tool_selection_json`，支持多轮 supplement 上下文（见 REFACTORING-PLAN）。

### 8.2 API

【必须】保持现有入口稳定：`POST /api/tickets/agent-runs`、supplement、SSE stream、audit、eval。

【建议】`AgentRunResponse` 可选增加 `gapAnalysisSummary`、`plannedSteps`，便于展示 Agent 推理链。

---

## 9. 测试与 Eval

项目必须证明不是手工 Demo。

### 9.1 测试层次

【必须】逐步具备：

- **单元测试**：规则、解析、完整度决策、优先级、路由。
- **集成测试**：submit、supplement、HITL、审计。
- **中间件集成**：PostgreSQL、Redis、Kafka、向量库真实路径（Docker Compose）。

### 9.2 Golden Case Eval

【必须】Golden Case **≥ 13** 条（含语义追问、OOM Plan 跳步 case）。

【必须】发布前核心 Eval **100% 通过**；开发中不低于 **80%**。

**必覆盖场景**：

- 信息不足（schema 追问）。
- **语义缺口追问**（超出固定 6 类模板，如批处理 / 偶发慢）。
- 支付 P1、OOMKilled、DB Connection Timeout。
- 权限、数据不一致、MQ 积压、缓存超时。
- 测试环境低优先级（P3）。
- 知识库无命中。
- MCP / Tool 失败降级。
- 需要人工确认。
- **AgentPlan 按场景选 Tool**（如 OOM 优先 metric）。

Eval 细节与 case ID 见 [docs/REFACTORING-PLAN.md §9](docs/REFACTORING-PLAN.md)。

---

## 10. Demo 与简历表达

### 10.1 Demo（5–8 分钟）

【必须】展示：

- 状态机驱动 Agent，不是 ChatBot。
- 信息不足会追问（含语义化追问），不会瞎分析。
- 信息完整后 RAG + Tool 取证；MCP 查真实日志 / 指标样例。
- Function Call 内部能力；根因有 evidence 链。
- P1/P0 进入 HITL；审计可见每一步。
- `POST /api/evals/run` 可跑通。

**话术围绕**：少追问 · 少错派 · 更快定位 · 可审计 · 可复盘 · 可扩展。

### 10.2 简历表达（唯一 canonical 版本）

```text
设计并实现企业级 Incident 工单处置 Agent 平台，基于 Spring Boot / Spring AI 构建 Plan-and-Execute 多步骤 Agent 状态机，结合语义缺口分析、RAG、Function Calling、MCP 工具调用、真实日志/指标取证和 Human-in-the-loop，实现工单结构化补全、场景化追问、根因分析、优先级评估、责任团队路由、Runbook 生成和全链路审计。系统支持 PostgreSQL/Redis/Kafka/Milvus 真实中间件环境、幂等与状态恢复，并通过 Golden Case Eval 保障 Agent 输出质量。
```

---

## 11. 技术栈

【必须】主栈：

- Java 21 · Spring Boot 3 · Spring AI · MyBatis-Plus
- PostgreSQL · Redis · Kafka · Milvus / pgvector
- MCP · Function Calling · SSE · Micrometer / Actuator · Docker Compose

【建议】CI：GitHub Actions 或等价方案。

**LangChain4j**：可作为单步 Analyzer、结构化输出、Retrieval 实验；**禁止**替换 `AgentRuntime`、决定优先级 / 路由、引入无法解释的自主循环。

---

## 12. 明确不做与 Roadmap

### 12.1 明确不做

【禁止】短期做：

- 通用聊天机器人、纯文档问答。
- 大而全工单系统 UI、多租户权限系统。
- 模型微调、自动执行生产变更。
- 无护栏 ReAct 无限 loop、单一 mega prompt 包办全流程。
- 与 Incident 处置无关、且无简历价值的技术堆叠。

任何新功能若不能增强「业务价值 + AI 能力 + 工程化 + 简历表达」，谨慎加入。

### 12.2 Roadmap

实施细节与文件清单见 [docs/REFACTORING-PLAN.md](docs/REFACTORING-PLAN.md)。

| 阶段 | 内容 | 状态 |
|------|------|------|
| **Phase 1** | InfoGapAnalysis、CompletenessDecision、场景化追问 | **已完成** |
| **Phase 2** | AgentPlanner、ToolSelector、按选中 Tool 取证 | **已完成** |
| **Phase 3** | `governance/` 包迁移、RoutingSuggestion、Eval 扩展 | **已完成** |
| **Future** | KNOWLEDGE_FEEDBACK、混合检索 ReRank、结案回灌 | 规划中 |

每完成一个 Phase，更新上表状态，并同步 [README.md](README.md) 流程说明。

---

## 附录：文档分工

| 文档 | 职责 |
|------|------|
| **AGENTS.md**（本文） | 原则、架构、红线、模块职责 |
| **docs/REFACTORING-PLAN.md** | 删改文件清单、Phase 计划、验收标准 |
| **README.md** | 启动、curl、Demo 步骤 |
| **docs/PROJECT-OVERVIEW.md** | 项目总览：定位 / 痛点 / 人群 / 闭环 / 流程 / 功能 / Roadmap |
| **docs/ARCHITECTURE.md**（可选） | 五层详图、时序图、面试深度问答 |
