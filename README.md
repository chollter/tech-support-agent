# OpsMind

面向求职展示的企业级 Incident / 工单处置 Agent 平台。项目目标是体现 **Java 高级工程师（AI 应用方向）** 所需的业务抽象、AI 工程化和生产级系统设计能力。

- 开发规则与架构宪法：[AGENTS.md](AGENTS.md)
- 项目总览：[docs/PROJECT-OVERVIEW.md](docs/PROJECT-OVERVIEW.md)
- 重构实施清单：[docs/REFACTORING-PLAN.md](docs/REFACTORING-PLAN.md)

## 业务价值

OpsMind 不做通用聊天机器人，而是替代工单系统中重复、麻烦但必须执行的工作：

- 用户描述模糊时自动追问（含语义化缺口分析），减少研发反复沟通。
- 将自然语言问题整理成结构化工单。
- 检索历史故障、SOP、Runbook 和错误码说明。
- 通过 MCP 查询真实可复现的日志/指标样例，形成证据链。
- 基于证据输出根因假设、优先级、责任团队和处理建议。
- P0/P1 或高风险事件进入 Human-in-the-loop。
- 全流程落库审计，支持复盘、追责和 Eval 回归。

## AI 能力点

- Spring AI 结构化抽取、语义缺口分析、步骤规划与建议生成，失败时规则降级。
- RAG：PostgreSQL 知识正文 + Milvus 向量检索。
- Function Call 风格内部工具：`searchSimilarCases`。
- MCP 风格外部工具：`query_logs`、`query_metric` 查询真实样例数据。
- Plan-and-Execute：`AgentPlanner` 按场景规划步骤，`ToolSelector` 白名单选 Tool（见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)）。
- RootCause：消费 RAG 命中和 Tool Evidence，输出 hypothesis、evidence、unknowns、confidence。
- Golden Case Eval：13 条，覆盖信息不足、语义追问、OOM Plan、支付/OOM/DB 等场景。

## 工程化能力点

- AgentRuntime 状态机编排（Plan-and-Execute 混合架构）。
- 短事务边界，避免 LLM/RAG/Tool 长耗时调用占用数据库连接。
- Redis 幂等键和 run 锁。
- Kafka 异步执行骨架，可通过 profile 开启。
- PostgreSQL 审计：AgentRun、AgentStep、ToolExecutionLog、PendingAction。
- Docker Compose 真实中间件：PostgreSQL、Redis、Kafka、Milvus。
- SSE 步骤流、Actuator/Micrometer 指标。

## 核心流程

目标状态机（详见 [AGENTS.md §4](AGENTS.md#4-目标状态机)）：

```text
SUBMITTED
  -> PREPROCESS
  -> TICKET_EXTRACT                         [L1 理解]
  -> INFO_GAP_ANALYSIS                      [L1 理解]
  -> COMPLETENESS_DECISION                  [L2 决策]
  -> [信息不足] FOLLOW_UP_QUESTION_GENERATE  [L2 决策]
  -> WAIT_USER_INPUT
  -> [信息足够] AGENT_PLAN                   [L2 决策]
  -> KNOWLEDGE_SEARCH                       [L3 执行]
  -> TOOL_SELECTION                         [L3 执行]
  -> EVIDENCE_COLLECTION                    [L3 执行]
  -> ROOT_CAUSE_ANALYSIS                    [L4 结论]
  -> PRIORITY_EVALUATION                    [L5 治理]
  -> TEAM_ROUTING                           [L5 治理]
  -> SUGGESTION_GENERATION                  [L4 结论]
  -> [高风险] WAIT_HUMAN_CONFIRM             [L5 治理]
  -> FINAL
```

> 重构进度：Phase 1–2 **已完成**；Phase 3 见 [docs/REFACTORING-PLAN.md](docs/REFACTORING-PLAN.md)。架构说明：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## 快速启动

```bash
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=milvus
```

浏览器打开：

```text
http://localhost:8020/
```

如果没有配置 `LLM_API_KEY`，系统会走规则/模板降级，核心 Demo 仍可运行。

## 常用接口

信息不足追问：

```bash
curl.exe -X POST http://localhost:8020/api/tickets/agent-runs ^
  -H "Content-Type: application/json" ^
  -d "{\"sessionId\":\"sess-001\",\"userId\":\"u-1001\",\"content\":\"接口报错了\",\"source\":\"WEB\"}"
```

完整故障分析：

```bash
curl.exe -X POST http://localhost:8020/api/tickets/agent-runs ^
  -H "Content-Type: application/json" ^
  -d "{\"sessionId\":\"sess-002\",\"userId\":\"u-1001\",\"content\":\"生产环境 payment-service Pod OOMKilled，从上午 10 点开始，多个用户支付失败，内存使用从 200Mi 飙升到 512Mi 后被 kill\",\"source\":\"WEB\"}"
```

SSE 步骤流：

```bash
curl.exe -N http://localhost:8020/api/tickets/agent-runs/{runId}/stream
```

审计：

```bash
curl.exe http://localhost:8020/api/audit/agent-runs/{runId}
```

运行 Eval：

```bash
curl.exe -X POST http://localhost:8020/api/evals/run
```

## 异步模式

默认接口仍同步返回完整分析结果，便于本地演示。开启 Kafka 异步执行：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=milvus -Dspring-boot.run.arguments="--opsmind.async.enabled=true"
```

异步模式下提交接口先返回 runId，后台通过 Kafka 消费 run execution event 推进 Agent 流程。

## 简历表达参考

完整 canonical 版本见 [AGENTS.md §10.2](AGENTS.md#102-简历表达唯一-canonical-版本)。

## 目录

```text
agent/           Agent 编排、Planner、SSE
execution/       L3 ToolSelector
ticket/          工单 API
extract/         L1 结构化抽取
understanding/   L1/L2 缺口分析、完整度决策、场景化追问
knowledge/       RAG 检索
execution/       L3 Tool 选择、Evidence（重构目标）
tool/            Function + MCP Tool 抽象
evidence/        Evidence 收集（重构中迁至 execution/）
analysis/        L4 根因分析
governance/      L5 优先级、路由、HITL（重构目标）
priority/        优先级规则（重构中迁至 governance/）
routing/         团队路由（重构中迁至 governance/）
suggestion/      L4 处理建议 + Runbook
human/           人工确认（重构中迁至 governance/）
persistence/     MyBatis-Plus
llm/             Spring AI 网关
eval/            Golden Case 评测
docs/            架构与重构文档
```
