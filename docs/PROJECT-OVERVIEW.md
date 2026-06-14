# OpsMind 项目总览

> 面向读者：面试官、新成员、自己复盘
> 一句话定位：不是 ChatBot，是**确定性企业 Incident 工单分析 Agent**——LLM 做理解与生成，规则做裁决，Java 做编排与审计。
> 关联：[AGENTS.md](../AGENTS.md)（开发宪法）· [ARCHITECTURE.md](ARCHITECTURE.md)（架构详图）· [REFACTORING-PLAN.md](REFACTORING-PLAN.md)（重构清单）· [README.md](../README.md)（启动 / Demo）

---

## 1. 项目定位

- **用途**：求职作品，对标 **Java 高级工程师（AI 应用方向）**，目标城市成都。
- **业务**：企业级 Incident / 工单智能处置 Agent（B 端内部工具，非 C 端聊天）。
- **范式**：**Plan-and-Execute**——Workflow（确定性）与 Agent Loop（LLM 自主）的折中。
- **取舍**：用业务复杂度承载工程深度，AI 能力点齐全可演示，每个功能经得起面试追问。**广度已够，下一阶段打深**。

---

## 2. 业务背景与痛点

工单处理中"重复、麻烦、低价值但必须做"的工作：

| 痛点 | 人工现状 | Agent 介入后 |
|------|----------|--------------|
| 信息不全来回追问 | 一线与用户扯皮 2-3 轮 | 语义缺口分析 + 场景化追问，首次响应即精准 |
| 派单靠经验 | 误派率高，多次流转 | 结构化抽取 + 规则路由 baseline |
| 排障靠老员工 | 知识不沉淀 | RAG 检索历史案例 / SOP / Runbook |
| 重复排障 | 同类故障反复人工查 | 相似案例复用，证据链沉淀 |

**核心业务指标**：MTTR（平均修复时间）↓ · 误派率↓ · 首次响应追问轮次↓ · 新人上手时间↓。

> AIOps / ITSM 领域真实问题（PagerDuty / ServiceNow 同赛道）。业务复杂度天然承载状态机、幂等、审计、降级、HITL 等高级工程能力——这是本项目相对"纯 RAG 问答"类作品的最大优势。

---

## 3. 目标用户

B 端内部工具，用户分层：

| 角色 | 与 Agent 的关系 |
|------|-----------------|
| 提单人（一线研发 / 测试 / SRE / 业务 / 客服） | Agent 输入方，拿到追问引导与建议 |
| **值班工程师 on-call**（核心服务对象） | 拿到结构化工单 + 证据链 + 根因 + Runbook，MTTR↓ |
| 二线专家 / 责任团队 | 被正确路由到，拿到已初步排查的工单 |
| 新人 | 靠知识检索 + Runbook 快速上手，不依赖老员工 |
| 管理者 / SRE 负责人 | 看 MTTR / 误派率 / 审计，做运营决策 |
| 最终用户（工单背后受影响的人） | 间接受益（故障更快恢复） |

---

## 4. 业务闭环

```text
【输入端：数据从哪来】
  ① 用户主动提交：工单门户 / IM 机器人 / 邮件 / 客服系统
  ② 系统自动生成：监控告警 / APM / 日志异常 / CI 失败
  ③ 外部系统对接：ITSM（ServiceNow / Jira）/ 上游业务系统
                       ↓ API / Kafka / Webhook / IM Bot
【Agent 处理：本项目核心】
  预处理 → 意图 → 抽取 → 完整度 → (追问) → 分析取证 → 根因 → 治理
                       ↓ 结构化结果 + 路由建议 + 证据链
【输出端：结果到哪去】分两条路
  路A 回提单人（低风险 / 咨询 / 信息不足）→ API 响应 / SSE：追问、建议、Runbook
  路B 发责任团队（高风险 / 生产 Incident）→ HITL 确认 → 通知值班人(IM/短信) + 建工单分派
```

- **接入现状**：✅ REST API（主动提交）· ✅ Kafka 异步（可演化为告警事件触发）· ❌ IM Bot / ITSM 集成（可选加分）。
- **分发现状与缺口**：路 A 已实现（HTTP / SSE 直接回提单人）；HITL 待确认已实现；**路 B「确认后真分发」是缺口**——`HumanConfirmController.confirm()` 之后只更新状态，无外部通知 / 工单系统集成。**待补 `NotificationService` 抽象**（防腐层 + 日志实现，生产可接 IM / ITSM）。

---

## 5. 核心架构

### 5.1 范式定位（重要，面试要讲对）

```text
纯 Workflow（确定性 100%） ←——→ Plan-Execute（本项目） ←——→ Agent Loop（Cursor / Claude Code）
                               Plan 阶段给 LLM 自主权           LLM 全自主循环
                               Execute 阶段确定性执行
```

- **不是** Cursor 那种 Agent Loop（LLM 自主循环调工具）。
- **不是** 纯 Workflow（每步固定硬编码）。
- **是** Plan-and-Execute：在固定框架内，把 LLM 自主权放在正确位置——Plan（选调查路径）、ToolSelector（选工具）、InfoGapAnalysis（场景化追问）。
- **卖点是「知道在哪用确定性、在哪给 LLM 自主权」**，不是"5 层"这个数字，更不是"提升 LLM 自主性"（5 层恰恰是提升可控性）。

### 5.2 五层架构

| 层 | 包 | 职责 | 调 LLM |
|----|----|------|--------|
| L0 接入治理 | `ticket/ infra/ audit/ async/` | 输入装配、幂等、锁、审计 | 否 |
| L1 理解 | `extract/ understanding/gap/` | 结构化抽取、语义缺口 | 是 |
| L2 决策 | `understanding/completeness/ understanding/followup/ agent/planner/` | 完整度合并、追问、步骤规划 | 混合 |
| L3 执行 | `knowledge/ execution/` | RAG、Tool 选择、Evidence | 混合 |
| L4 结论 | `analysis/ suggestion/` | 根因、Runbook / 建议 | 是 |
| L5 治理输出 | `governance/ human/` | 优先级、路由、HITL | 规则为主 |

> 五层是**业务流程分层**；另有**横切关注点**（LLM 治理 / 可观测性 / 安全 / 配置）跨层存在，不归任一层。

### 5.3 三层自主边界

| 角色 | 做什么 |
|------|--------|
| LLM | 理解、语义缺口、Plan、Tool 选择、根因、建议 |
| Java | 状态机编排、事务、幂等、超时、降级、审计 |
| Policy 规则 | 优先级、HITL、责任团队——**LLM 不可 override** |

> 口号：**LLM 负责听懂和规划，Policy Engine 负责裁决，Java 负责执行和审计。**

---

## 6. 业务流程（端到端）

| # | 步骤 | 做什么 | 调 LLM | 产出 | 门控 / 分流 |
|---|------|--------|--------|------|-------------|
| 0 | 接入预处理 | 校验、截断、敏感词、幂等、锁 | 否 | 清洗内容 + traceId | — |
| 1 | 意图分类 | INCIDENT / CONSULT / DUPLICATE | 规则优先 | IssueType | CONSULT→简化；DUPLICATE→关联 |
| 2 | 去重检测 | 查近期相似工单 | 否 | 是否重复 | 重复→不重跑 |
| 3 | 结构化抽取 | system / module / api / error / env / impact / time | 是（规则兜底） | TicketExtractResult | — |
| 4 | 语义缺口 | schema 外的缺口 | 是 | InfoGapAnalysis | — |
| 5 | 完整度决策 | schema + 语义 合并，分级 | 否（Java） | 完整度等级 | 充分→分析；部分→分层输出；严重不足→追问 |
| 6a | 追问 | 阻断性优先 | 是（模板兜底） | 追问列表 | → WAIT_USER_INPUT |
| 6b | 分析路径 | 见下 | — | — | — |
| ↳7 | AgentPlan | 规划调查步骤 | 是（规则兜底） | AgentPlan | — |
| ↳8 | 知识检索 | 相似案例 / SOP | 否（向量） | KnowledgeHit[] | 命中→指导工具选择 |
| ↳9 | 工具选择 | 白名单内选 | 是（规则兜底） | ToolSelection | — |
| ↳10 | 证据收集 | 执行选中工具 | 否 | ToolResult[] | 证据充分性：不够→受控补取 |
| ↳11 | 根因 | 基于证据生成假设 | 是 | RootCauseResult | **置信度门控**：低→HITL |
| ↳12 | 优先级 | P0-P3 | 否 | PriorityResult | — |
| ↳13 | 路由 | 责任团队 | 否 + LLM 建议 merge | RoutingResult | 低置信→HITL |
| ↳14 | 建议生成 | Runbook + 带来源 | 是 | TicketSuggestion | **来源约束**：无来源声明为推断 |
| 15 | HITL 闸门 | P0/P1 / 低置信 | 否 | 是否确认 | → WAIT_HUMAN_CONFIRM |
| 16 | 输出 + 审计 | 结构化结果 + 全链路审计 | 否 | AgentRunResponse | FINAL |

### "接口报错了"走查

```text
"接口报错了"
 → 意图=INCIDENT，去重无命中
 → 抽取：字段几乎全空
 → 缺口：哪个接口？什么错？环境？时间？影响？
 → 完整度=严重不足（连接口都不知道）
 → 追问（阻断性优先）："哪个接口/服务？报什么错？"
 → WAIT_USER_INPUT
```

用户补充"支付回调接口，超时，生产，10 分钟前，全量订单失败"后：

```text
 → 重新走完整度决策（续跑，不从头）
 → 完整度=充分
 → Plan：查 metric + 查日志
 → 知识检索：命中"支付超时"案例 → 提示 DB 连接池 / 下游
 → 工具：query_metric（RT）+ query_logs（错误日志）
 → 证据：RT 飙升 + DB connection timeout
 → 根因：DB 连接池耗尽（confidence=0.8，引用日志 + metric + 案例）
 → 优先级：P1（生产 + 全量）
 → 路由：支付团队（规则命中）
 → 建议：扩连接池 + 排查慢 SQL（来源：案例 #xxx + SOP #yyy）
 → HITL：P1 → WAIT_HUMAN_CONFIRM
```

---

## 7. 功能清单

### AI 能力
- Spring AI 结构化抽取 / 语义缺口 / Plan / 工具选择 / 根因 / 建议（每项规则降级）
- RAG：PostgreSQL 正文 + Milvus / pgvector 向量
- Function Calling：`searchSimilarCases`
- MCP 风格工具：`query_logs` / `query_metric`（真实样例数据）
- Plan-and-Execute：AgentPlanner + ToolSelector + EvidenceCollection
- 结构化输出：StructuredOutputParser
- Golden Case Eval：14 条

### 工程能力
- AgentRuntime 状态机编排（Plan-Execute）
- 短事务边界 + Redis 幂等键 + run 锁
- Kafka 异步执行骨架（可 profile 开启）
- PostgreSQL 全链路审计（AgentRun / AgentStep / ToolExecutionLog / PendingAction）
- Docker Compose 真实中间件（PG / Redis / Kafka / Milvus）
- SSE 步骤流 + Actuator / Micrometer 指标

---

## 8. 方案设计与 Roadmap

按「对标高级工程师 + 求职尽快可演示」的性价比排序。

### P0 必做（不补面试露怯 / 闭环必需）
| 项 | 价值 | 状态 |
|----|------|------|
| ~~LLM 调用工程化治理~~ | 超时分层生效、重试异常分类、token 解析与指标。AI 应用岗必考点 | ✅ 已完成（ExternalCallGateway） |
| NotificationService 分发层 | 业务闭环最后一步（confirm → 通知），防腐层 + 日志实现 | 待做 |
| 意图分流前置 | CONSULT 走简化路径，省 LLM 调用，真实工程优化 | 待做 |

### P1 推荐（拉开「高级」差距）
| 项 | 价值 |
|----|------|
| 根因置信度门控 + 证据约束 | 防幻觉，低置信不直接拍板 |
| 分层输出（部分充分给初步结论） | 代替「全或无」门控，最体现高级思想 |
| 评测体系打深 | 量化指标 + prompt / 模型 A/B + CI 回归 |
| 状态机可恢复化 | status 驱动，支持中断恢复 / 异步推进 / 多轮续跑 |

### P2 可选（锦上添花）
- 去重 / 相似检测 · 证据充分性受控补取 · 可观测补全（traceId / token） · 熔断限流 · 向量 rerank · IM Bot Mock

### 明确不做
- ❌ 加新业务功能（广度够了，要打深）· ❌ 换语言 / 框架 · ❌ 微服务化 · ❌ 无护栏 ReAct · ❌ 删除规则治理层

---

## 9. 简历对标（AI 应用岗能力覆盖度）

| 能力 | 覆盖 | 备注 |
|------|------|------|
| RAG | ✅ | pgvector / Milvus |
| Function Calling | ✅ | searchSimilarCases |
| MCP / Tool Use | ✅ 概念 | query_logs / query_metric，需确认是否真 MCP 协议 |
| Agent 编排（Plan-Execute） | ✅ | 核心卖点 |
| 结构化输出 | ✅ | StructuredOutputParser |
| LLM 治理（超时 / 重试 / 降级） | ✅ | ExternalCallGateway（Resilience4j）+ DashScope readTimeout 分层 |
| Token / Context Window | ✅ | LlmGateway 解析 usage，CallMetrics 埋 ai_token_usage |
| 评测 Eval | ✅ | 14 case，P1 打深 |
| 可观测性 | ⚠️ | 部分，P1 补 |
| HITL | ✅ | 治理亮点 |
| 幂等 / 状态恢复 | ⚠️ | 幂等有、状态恢复弱，P1 补 |

> **LLM 治理 + Token 已补齐**（ExternalCallGateway + token 解析）；权衡与已知限制见 [docs/KNOWN-ISSUES.md](KNOWN-ISSUES.md)。

---

## 10. 关键设计原则（面试可讲）

1. **规则能做的不调 LLM**——意图 / 去重 / 优先级 / 路由 baseline / HITL 全用规则，省成本、快、确定。
2. **门控前置省 LLM 调用**——信息不足不进 RAG / 工具 / 根因。
3. **意图分流**——不同意图不同重量级流程。
4. **分层输出代替全或无**——部分充分给带置信度的初步结论。
5. **证据约束 + 置信度门控防幻觉**——根因 / 建议必须有据，低置信不拍板。
6. **HITL 兜底高风险**——P0 / P1 / 低置信不交给 AI 自动决策，Agent 辅助不自主执行。
