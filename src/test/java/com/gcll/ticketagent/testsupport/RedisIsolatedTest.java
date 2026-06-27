package com.gcll.ticketagent.testsupport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 需要隔离 Redis 状态的 @SpringBootTest 基类。
 *
 * <p><b>背景</b>：@SpringBootTest 跑工单会写幂等键(idempotency:*)到 Redis。
 * 多个测试类共用同一 Redis db 时，前一个测试的幂等键残留会被后一个测试命中，
 * 导致 {@code Agent run not found}（幂等命中后查不到对应 run）。
 *
 * <p><b>解法</b>：测试统一用 db=15（见 application-test.yml 的 spring.data.redis.database），
 * 与真实应用 db0 隔离；并在每个测试类启动前清空 db15，消除测试间互相污染。
 *
 * <p>{@link TestInstance}(PER_CLASS) 让 @BeforeAll 能用注入的实例方法（非 static），
 * 否则 @BeforeAll 必须是 static，无法 @Autowired StringRedisTemplate。
 *
 * <p>用法：{@code class XxxTest extends RedisIsolatedTest { ... }}（继承 @SpringBootTest）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class RedisIsolatedTest {

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @BeforeAll
    void clearTestRedis() {
        // 测试专用 db15，清空避免上一轮测试的幂等键/锁残留污染本轮
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }
}
