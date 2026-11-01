package com.aidianping.service.order;

import cn.hutool.json.JSONUtil;
import com.aidianping.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 不依赖请求线程。只在成功或失败已留档后确认消息。 */
@Slf4j
public class OrderMessageProcessor {
    public static final int MAX_ATTEMPTS = 5;
    public static final String RETRY_PREFIX = "seckill:order:retry:g1:";
    public static final String FAILURE_PREFIX = "seckill:order:failed:g1:";
    private static final DefaultRedisScript<Long> ACK_SCRIPT = script("order-ack.lua");
    private static final DefaultRedisScript<Long> FAILURE_SCRIPT = script("order-failure.lua");

    private final StringRedisTemplate redis;
    private final Function<VoucherOrder, OrderProcessingResult> createOrder;
    private final Clock clock;

    public OrderMessageProcessor(StringRedisTemplate redis,
            Function<VoucherOrder, OrderProcessingResult> createOrder, Clock clock) {
        this.redis = redis;
        this.createOrder = createOrder;
        this.clock = clock;
    }

    private static DefaultRedisScript<Long> script(String resource) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(resource));
        script.setResultType(Long.class);
        return script;
    }

    public void process(MapRecord<String, Object, Object> record) {
        String messageId = record.getId().getValue();
        String retryKey = RETRY_PREFIX + messageId;
        String failureKey = FAILURE_PREFIX + messageId;
        Map<Object, Object> payload = record.getValue();

        // 失败已留档后绝不再次创建订单，即使前次 ACK 响应丢失。
        if (Boolean.TRUE.equals(redis.hasKey(failureKey))) {
            acknowledge(messageId, retryKey);
            return;
        }
        if (payload.size() == 1 && "bootstrap".equals(payload.get("type"))) {
            acknowledge(messageId, retryKey);
            return;
        }
        Object previousAttempts = redis.opsForHash().get(retryKey, "attempts");
        if (previousAttempts != null && Long.parseLong(previousAttempts.toString()) >= MAX_ATTEMPTS) {
            Object lastError = redis.opsForHash().get(retryKey, "lastError");
            fail(record, retryKey, failureKey,
                    lastError == null ? "RETRY_EXHAUSTED" : lastError.toString(),
                    Long.parseLong(previousAttempts.toString()));
            return;
        }
        Object nextAttempt = redis.opsForHash().get(retryKey, "nextAttemptAt");
        if (nextAttempt != null && Long.parseLong(nextAttempt.toString()) > clock.millis()) {
            return;
        }

        VoucherOrder order;
        try {
            order = new VoucherOrder();
            // 兼容AI点评原始消息的 id 字段。
            Object orderId = payload.containsKey("orderId") ? payload.get("orderId") : payload.get("id");
            order.setId(positiveLong(orderId));
            order.setUserId(positiveLong(payload.get("userId")));
            order.setVoucherId(positiveLong(payload.get("voucherId")));
        } catch (IllegalArgumentException e) {
            fail(record, retryKey, failureKey, "INVALID_MESSAGE", 0);
            return;
        }

        OrderProcessingResult result;
        String reason;
        try {
            result = createOrder.apply(order);
            reason = result == null ? "EMPTY_PROCESSING_RESULT" : result.name();
        } catch (RuntimeException e) {
            result = OrderProcessingResult.RETRY_LATER;
            reason = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        }
        if (result == OrderProcessingResult.CREATED || result == OrderProcessingResult.ALREADY_EXISTS) {
            acknowledge(messageId, retryKey);
            return;
        }
        if (result == OrderProcessingResult.OUT_OF_STOCK
                || result == OrderProcessingResult.DUPLICATE_CONFLICT
                || result == OrderProcessingResult.INVALID_MESSAGE) {
            fail(record, retryKey, failureKey, reason,
                    previousAttempts == null ? 1 : Long.parseLong(previousAttempts.toString()) + 1);
            return;
        }

        // ACK/Redis 通信异常不在上面的数据库 catch 内，不能误算为落库失败。
        Long attempts = redis.opsForHash().increment(retryKey, "attempts", 1L);
        if (attempts == null) {
            throw new IllegalStateException("无法保存订单重试次数");
        }
        redis.opsForHash().put(retryKey, "lastError", truncate(reason));
        if (attempts >= MAX_ATTEMPTS) {
            fail(record, retryKey, failureKey, reason, attempts);
            return;
        }
        long delay = Math.min(200L << (attempts.intValue() - 1), 5000L);
        redis.opsForHash().putAll(retryKey, Map.of(
                "nextAttemptAt", Long.toString(clock.millis() + delay),
                "lastError", truncate(reason)));
    }

    private long positiveLong(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("缺少订单必要字段");
        }
        long number = Long.parseLong(value.toString());
        if (number <= 0) {
            throw new IllegalArgumentException("订单字段必须大于0");
        }
        return number;
    }

    private void acknowledge(String messageId, String retryKey) {
        Long result = redis.execute(ACK_SCRIPT, List.of("stream.orders", retryKey), "g1", messageId);
        if (result == null) {
            throw new IllegalStateException("订单消息确认结果未知");
        }
    }

    private void fail(MapRecord<String, Object, Object> record, String retryKey,
            String failureKey, String reason, long attempts) {
        Long result = redis.execute(FAILURE_SCRIPT,
                List.of("stream.orders", retryKey, failureKey),
                "g1", record.getId().getValue(), JSONUtil.toJsonStr(record.getValue()),
                truncate(reason), Long.toString(attempts), Long.toString(clock.millis()));
        if (result == null) {
            throw new IllegalStateException("订单失败留档结果未知，保留Pending等待恢复");
        }
        log.warn("订单转入失败记录，messageId={}, reason={}, failureKey={}",
                record.getId(), truncate(reason), failureKey);
    }

    private String truncate(String value) {
        return value.substring(0, Math.min(value.length(), 1000));
    }
}
