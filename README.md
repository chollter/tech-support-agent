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
