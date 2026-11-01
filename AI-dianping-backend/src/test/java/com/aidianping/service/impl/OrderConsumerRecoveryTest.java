package com.aidianping.service.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderConsumerRecoveryTest {
    @Test
    void pendingScanAdvancesPastBrokenRecordAndResetsAfterReachingEnd() throws Exception {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        // 模拟失败留档时 Redis 故障：处理器异常不能阻止游标前进。
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("redis unavailable"));
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        Constructor<?> constructor = Class.forName(VoucherOrderServiceImpl.class.getName()
                + "$VoucherOrderHandler").getDeclaredConstructor(VoucherOrderServiceImpl.class);
        constructor.setAccessible(true);
        Object handler = constructor.newInstance(service);
        MapRecord<String, Object, Object> record = StreamRecords.newRecord()
                .in("stream.orders").ofMap(Map.<Object, Object>of("userId", "1001"))
                .withId(RecordId.of("100-0"));
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record), List.of());

        ReflectionTestUtils.invokeMethod(handler, "handlePendingList");
        assertEquals("100-0", ReflectionTestUtils.getField(handler, "pendingCursor"));
        ReflectionTestUtils.invokeMethod(handler, "handlePendingList");
        assertEquals("0", ReflectionTestUtils.getField(handler, "pendingCursor"));

        ArgumentCaptor<StreamOffset<String>> offset = ArgumentCaptor.forClass(StreamOffset.class);
        verify(streams, times(2)).read(eq(Consumer.from("g1", "c1")),
                any(StreamReadOptions.class), offset.capture());
        assertEquals("0", offset.getAllValues().get(0).getOffset().getOffset());
        assertEquals("100-0", offset.getAllValues().get(1).getOffset().getOffset());
    }
}
