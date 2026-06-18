package com.example.opsmcp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 运维日志/指标查询的 STDIO MCP Server。
 * <p>由 tech-support-agent 主应用通过 {@code mcp-servers.json} 拉起为子进程，
 * 经标准输入输出通信。暴露 {@code query_logs} / {@code query_metric} 两个 MCP tool，
 * 背后查 PostgreSQL 的 {@code ops_log_sample} / {@code ops_metric_sample} 表。
 */
@SpringBootApplication
@MapperScan("com.example.opsmcp.mapper")
public class OpsMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsMcpServerApplication.class, args);
    }
}
