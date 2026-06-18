CREATE TABLE IF NOT EXISTS ops_log_sample (
    id              VARCHAR(64)  PRIMARY KEY,
    system_name     VARCHAR(128) NOT NULL,
    service_name    VARCHAR(128) NOT NULL,
    module_name     VARCHAR(128),
    environment     VARCHAR(32)  NOT NULL,
    level           VARCHAR(16)  NOT NULL,
    occurred_at     TIMESTAMP    NOT NULL,
    trace_id        VARCHAR(128),
    message         TEXT         NOT NULL,
    tags            TEXT
);

CREATE TABLE IF NOT EXISTS ops_metric_sample (
    id              VARCHAR(64)  PRIMARY KEY,
    system_name     VARCHAR(128) NOT NULL,
    service_name    VARCHAR(128) NOT NULL,
    metric_name     VARCHAR(128) NOT NULL,
    environment     VARCHAR(32)  NOT NULL,
    occurred_at     TIMESTAMP    NOT NULL,
    metric_value    DOUBLE PRECISION NOT NULL,
    unit            VARCHAR(32),
    labels          TEXT,
    status          VARCHAR(64)
);

INSERT INTO ops_log_sample VALUES
('l1', 'payment', 'payment-service', 'callback', 'production', 'ERROR',
 '2026-06-18 10:00:00', 'trace-001', 'OOMKilled memory exceeded', 'java,heap'),
('l2', 'payment', 'payment-service', 'callback', 'production', 'WARN',
 '2026-06-18 10:05:00', 'trace-002', 'connection pool exhausted', 'timeout');

INSERT INTO ops_metric_sample VALUES
('m1', 'payment', 'payment-service', 'jvm.memory.heap', 'production',
 '2026-06-18 10:00:00', 512.0, 'Mi', 'area=heap', 'CRITICAL'),
('m2', 'payment', 'payment-service', 'http.server.requests', 'production',
 '2026-06-18 10:01:00', 500.0, 'count', 'uri=/pay/callback', 'WARNING');
