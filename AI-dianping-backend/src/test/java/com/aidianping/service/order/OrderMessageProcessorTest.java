package com.aidianping.service.order;

import com.aidianping.entity.VoucherOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderMessageProcessorTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    private final Function<VoucherOrder, OrderProcessingResult> create = mock(Function.class);
    private final OrderMessageProcessor processor = new OrderMessageProcessor(redis, create,
            Clock.fixed(Instant.ofEpochMilli(10000), ZoneOffset.UTC));
    private final MapRecord<String, Object, Object> record = message(Map.of(
            "orderId", "99", "userId", "1001", "voucherId", "10"));
    private final String retryKey = OrderMessageProcessor.RETRY_PREFIX + "100-0";
    private final String failureKey = OrderMessageProcessor.FAILURE_PREFIX + "100-0";

    private static MapRecord<String, Object, Object> message(Map<String, String> values) {
        Map<Object, Object> payload = new java.util.HashMap<>(values);
        return StreamRecords.newRecord().in("stream.orders").ofMap(payload).withId(RecordId.of("100-0"));
    }

    @BeforeEach
    void setUp() {
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        when(hashes.increment(retryKey, "attempts", 1L)).thenReturn(1L);
    }

    @Test
    void successfulOrderAcknowledgesAndCleansRetryState() {
        when(create.apply(any())).thenReturn(OrderProcessingResult.CREATED);
        processor.process(record);
        verify(redis).execute(any(RedisScript.class), eq(List.of("stream.orders", retryKey)),
                eq("g1"), eq("100-0"));
        verify(hashes, never()).increment(anyString(), any(), anyLong());
        ArgumentCaptor<VoucherOrder> order = ArgumentCaptor.forClass(VoucherOrder.class);
        verify(create).apply(order.capture());
        assertEquals(99L, order.getValue().getId());
    }

    @Test
    void alreadyCommittedOrderCanAcknowledgeAfterPreviousAckFailure() {
        when(create.apply(any())).thenReturn(OrderProcessingResult.ALREADY_EXISTS);
        processor.process(record);
        verify(redis).execute(any(RedisScript.class), eq(List.of("stream.orders", retryKey)),
                eq("g1"), eq("100-0"));
    }

    @Test
    void retryableFalseOutcomeMustNotAcknowledge() {
        when(create.apply(any())).thenReturn(OrderProcessingResult.RETRY_LATER);
        processor.process(record);
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
        verify(hashes).putAll(eq(retryKey), argThat(state -> "10200".equals(state.get("nextAttemptAt"))));
    }

    @Test
    void databaseExceptionIsRetriedWithoutAcknowledgement() {
        when(create.apply(any())).thenThrow(new IllegalStateException("database unavailable"));
        processor.process(record);
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
        verify(hashes).put(eq(retryKey), eq("lastError"), contains("database unavailable"));
    }

    @Test
    void persistedBackoffPreventsImmediateDatabaseRetry() {
        when(hashes.get(retryKey, "nextAttemptAt")).thenReturn("11000");
        processor.process(record);
        verifyNoInteractions(create);
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void fifthFailureUsesSingleArchiveAndAckScript() {
        when(create.apply(any())).thenReturn(OrderProcessingResult.RETRY_LATER);
        when(hashes.increment(retryKey, "attempts", 1L)).thenReturn(5L);
        processor.process(record);
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("stream.orders", retryKey, failureKey)),
                eq("g1"), eq("100-0"), contains("orderId"), eq("RETRY_LATER"), eq("5"), eq("10000"));
    }

    @Test
    void exhaustedRetriesAfterRestartOnlyRetryArchiving() {
        when(hashes.get(retryKey, "attempts")).thenReturn("5");
        when(hashes.get(retryKey, "lastError")).thenReturn("database unavailable");
        processor.process(record);
        verifyNoInteractions(create);
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("stream.orders", retryKey, failureKey)),
                eq("g1"), eq("100-0"), anyString(), eq("database unavailable"), eq("5"), eq("10000"));
    }

    @Test
    void failureArchiveExceptionMustNotSendSeparateAck() {
        when(create.apply(any())).thenReturn(OrderProcessingResult.OUT_OF_STOCK);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("archive unavailable"));
        assertThrows(IllegalStateException.class, () -> processor.process(record));
        verify(redis, never()).execute(any(RedisScript.class),
                eq(List.of("stream.orders", retryKey)), any(Object[].class));
    }

    @Test
    void existingFailureRecordNeverCreatesOrderAgain() {
        when(redis.hasKey(failureKey)).thenReturn(true);
        processor.process(record);
        verifyNoInteractions(create);
        verify(redis).execute(any(RedisScript.class), eq(List.of("stream.orders", retryKey)),
                eq("g1"), eq("100-0"));
    }

    @Test
    void onlyBootstrapMayBeSilentlySkipped() {
        processor.process(message(Map.of("userId", "1001")));
        verifyNoInteractions(create);
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("stream.orders", retryKey, failureKey)),
                eq("g1"), eq("100-0"), anyString(), eq("INVALID_MESSAGE"), eq("0"), eq("10000"));
    }

    @Test
    void legacyIdFieldIsStillConsumed() {
        when(create.apply(any())).thenReturn(OrderProcessingResult.CREATED);
        processor.process(message(Map.of("id", "99", "userId", "1001", "voucherId", "10")));
        verify(create).apply(argThat(order -> order.getId() == 99L));
    }

    @Test
    void ackTransportExceptionIsNotCountedAsDatabaseFailure() {
        when(create.apply(any())).thenReturn(OrderProcessingResult.CREATED);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("ack response lost"));
        assertThrows(IllegalStateException.class, () -> processor.process(record));
        verify(hashes, never()).increment(anyString(), any(), anyLong());
    }
}
