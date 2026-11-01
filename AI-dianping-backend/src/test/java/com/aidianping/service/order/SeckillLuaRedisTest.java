package com.aidianping.service.order;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 显式启用的真实 Redis 联调；使用独立 key 和测试 Stream，不产生业务订单消息。 */
@EnabledIfEnvironmentVariable(named = "SECKILL_REDIS_TEST", matches = "true")
class SeckillLuaRedisTest {
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static String source;
    private String voucherId;
    private String stockKey;
    private String timeKey;
    private String orderKey;
    private String stream;
    private static final long NOW = 2_000_000L;

    @BeforeAll
    static void connect() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("SECKILL_REDIS_PORT", "6380"));
        factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        try (var input = new ClassPathResource("seckill.lua").getInputStream()) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void prepare() {
        voucherId = "time-test:" + UUID.randomUUID();
        stockKey = "seckill:stock:" + voucherId;
        timeKey = "seckill:time:" + voucherId;
        orderKey = "seckill:order:" + voucherId;
        stream = "test:seckill:orders:" + voucherId;
        redis.opsForValue().set(stockKey, "100");
    }

    @AfterEach
    void clean() {
        redis.delete(List.of(stockKey, timeKey, orderKey, stream));
    }

    @AfterAll
    static void close() {
        if (factory != null) factory.destroy();
    }

    private void window(long begin, long end) {
        redis.opsForHash().putAll(timeKey, Map.of(
                "beginTime", Long.toString(begin), "endTime", Long.toString(end)));
    }

    private Long execute(boolean fixedTime) {
        String lua = source.replace("stream.orders", stream);
        if (fixedTime) {
            // 仅边界测试固定时钟；实际执行的仍是资源脚本中的 Redis 操作和条件分支。
            lua = lua.replace("local serverTime = redis.call('time')",
                    "local serverTime = {'2000', '0'}");
        }
        return redis.execute(new DefaultRedisScript<>(lua, Long.class), List.of(),
                voucherId, "1001", "99");
    }

    private void unchanged() {
        assertEquals("100", redis.opsForValue().get(stockKey));
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(orderKey, "1001")));
        assertEquals(0L, redis.opsForStream().size(stream));
    }

    @Test
    void beforeStartDoesNotDeductOrEnqueue() {
        window(NOW + 1, NOW + 1000);
        assertEquals(3L, execute(true));
        unchanged();
    }

    @Test
    void exactlyAtStartIsAccepted() {
        window(NOW, NOW + 1000);
        assertEquals(0L, execute(true));
        assertEquals("99", redis.opsForValue().get(stockKey));
        assertEquals(1L, redis.opsForStream().size(stream));
    }

    @Test
    void exactlyAtEndIsRejected() {
        window(NOW - 1000, NOW);
        assertEquals(4L, execute(true));
        unchanged();
    }

    @Test
    void missingWindowFailsClosed() {
        assertEquals(5L, execute(true));
        unchanged();
    }

    @Test
    void invalidWindowFailsClosed() {
        window(NOW + 1000, NOW - 1000);
        assertEquals(5L, execute(true));
        unchanged();
    }

    @Test
    void malformedWindowFailsClosed() {
        redis.opsForHash().putAll(timeKey, Map.of("beginTime", "bad", "endTime", "bad"));
        assertEquals(5L, execute(true));
        unchanged();
    }

    @Test
    void acceptedUserStillCannotBuyTwice() {
        window(NOW - 1000, NOW + 1000);
        assertEquals(0L, execute(true));
        assertEquals(2L, execute(true));
        assertEquals("99", redis.opsForValue().get(stockKey));
        assertEquals(1L, redis.opsForStream().size(stream));
    }

    @Test
    void unmodifiedServerClockIsUsedInRealExecution() {
        window(0, Long.MAX_VALUE / 1000);
        assertEquals(0L, execute(false));
        assertEquals("99", redis.opsForValue().get(stockKey));
    }
}
