package com.aidianping.service.impl;

import com.aidianping.controller.VoucherController;
import com.aidianping.dto.Result;
import com.aidianping.dto.UserDTO;
import com.aidianping.entity.SeckillVoucher;
import com.aidianping.entity.Voucher;
import com.aidianping.service.ISeckillVoucherService;
import com.aidianping.service.IVoucherService;
import com.aidianping.utils.RedisIdWorker;
import com.aidianping.utils.SeckillTimeWindow;
import com.aidianping.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeckillTimeValidationTest {
    private final LocalDateTime begin = LocalDateTime.of(2026, 11, 1, 10, 0);
    private final LocalDateTime end = begin.plusHours(2);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ISeckillVoucherService stockService = mock(ISeckillVoucherService.class);

    @AfterEach
    void cleanUser() {
        UserHolder.removeUser();
    }

    private VoucherServiceImpl voucherService() {
        VoucherServiceImpl service = spy(new VoucherServiceImpl());
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "seckillVoucherService", stockService);
        return service;
    }

    @Test
    void businessTimesAreConvertedFromBeijingToAbsoluteMilliseconds() {
        var window = SeckillTimeWindow.redisFields(begin, end);
        assertEquals(Long.toString(Instant.parse("2026-11-01T02:00:00Z").toEpochMilli()), window.get("beginTime"));
        assertEquals(Long.toString(Instant.parse("2026-11-01T04:00:00Z").toEpochMilli()), window.get("endTime"));
    }

    @Test
    void missingEqualAndReversedTimesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SeckillTimeWindow.redisFields(null, end));
        assertThrows(IllegalArgumentException.class, () -> SeckillTimeWindow.redisFields(begin, null));
        assertThrows(IllegalArgumentException.class, () -> SeckillTimeWindow.redisFields(begin, begin));
        assertThrows(IllegalArgumentException.class, () -> SeckillTimeWindow.redisFields(end, begin));
    }

    @Test
    void invalidTimeDoesNotWriteDatabaseOrRedis() {
        VoucherServiceImpl service = voucherService();
        Voucher voucher = new Voucher().setStock(10).setBeginTime(end).setEndTime(begin);
        assertThrows(IllegalArgumentException.class, () -> service.addSeckillVoucher(voucher));
        verify(service, never()).save(any());
        verifyNoInteractions(redis, stockService);
    }

    @Test
    void nonpositiveStockIsRejectedBeforeSaving() {
        VoucherServiceImpl service = voucherService();
        assertThrows(IllegalArgumentException.class, () -> service.addSeckillVoucher(
                new Voucher().setStock(0).setBeginTime(begin).setEndTime(end)));
        verify(service, never()).save(any());
    }

    @Test
    void newActivityWritesWindowBeforeMakingStockAvailable() {
        VoucherServiceImpl service = voucherService();
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(call -> {
            call.<Voucher>getArgument(0).setId(10L);
            return true;
        }).when(service).save(any(Voucher.class));
        when(stockService.save(any())).thenReturn(true);
        service.addSeckillVoucher(new Voucher().setStock(100).setBeginTime(begin).setEndTime(end));
        var ordered = inOrder(hashes, values);
        ordered.verify(hashes).putAll("seckill:time:10", SeckillTimeWindow.redisFields(begin, end));
        ordered.verify(values).set("seckill:stock:10", "100");
        verify(stockService).save(argThat(voucher -> voucher.getVoucherId() == 10L
                && begin.equals(voucher.getBeginTime()) && end.equals(voucher.getEndTime())));
    }

    @Test
    void failedDatabaseSaveDoesNotPublishRedisActivity() {
        VoucherServiceImpl service = voucherService();
        doReturn(false).when(service).save(any(Voucher.class));
        assertThrows(IllegalStateException.class, () -> service.addSeckillVoucher(
                new Voucher().setStock(100).setBeginTime(begin).setEndTime(end)));
        verifyNoInteractions(redis, stockService);
    }

    @Test
    void startupLoadsLegacyActivityTimeWithoutOverwritingReservedStock() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "seckillVoucherService", stockService);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        when(stockService.list()).thenReturn(List.of(new SeckillVoucher().setVoucherId(10L)
                .setStock(100).setBeginTime(begin).setEndTime(end)));
        ReflectionTestUtils.invokeMethod(service, "preloadSeckillStock");
        verify(hashes).putAll("seckill:time:10", SeckillTimeWindow.redisFields(begin, end));
        verify(values).setIfAbsent("seckill:stock:10", "100");
        verify(values, never()).set(anyString(), anyString());
    }

    @Test
    void startupDisablesInvalidActivityInsteadOfLeavingStaleWindow() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "seckillVoucherService", stockService);
        when(stockService.list()).thenReturn(List.of(new SeckillVoucher().setVoucherId(10L).setStock(100)));
        ReflectionTestUtils.invokeMethod(service, "preloadSeckillStock");
        verify(redis).delete("seckill:time:10");
        verify(redis, never()).opsForValue();
    }

    @ParameterizedTest
    @CsvSource({"1,库存不足", "2,不能重复下单", "3,秒杀尚未开始", "4,秒杀已经结束", "5,秒杀活动时间未配置或无效"})
    void scriptRejectionsHaveDistinctBusinessMessages(long code, String expected) {
        VoucherOrderServiceImpl service = orderService(code);
        Result result = service.seckillVoucher(10L);
        assertFalse(result.getSuccess());
        assertEquals(expected, result.getErrorMsg());
    }

    @Test
    void acceptedRequestStillReturnsOrderId() {
        Result result = orderService(0L).seckillVoucher(10L);
        assertTrue(result.getSuccess());
        assertEquals(99L, result.getData());
    }

    private VoucherOrderServiceImpl orderService(Long code) {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        RedisIdWorker ids = mock(RedisIdWorker.class);
        when(ids.nextId("order")).thenReturn(99L);
        ReflectionTestUtils.setField(service, "redisIdWorker", ids);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        UserDTO user = new UserDTO();
        user.setId(1001L);
        UserHolder.saveUser(user);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(code);
        return service;
    }

    @Test
    void invalidCreationReturnsBusinessMessageFromController() {
        IVoucherService service = mock(IVoucherService.class);
        doThrow(new IllegalArgumentException("秒杀结束时间必须晚于开始时间"))
                .when(service).addSeckillVoucher(any());
        Result result = new VoucherController(service).addSeckillVoucher(new Voucher());
        assertFalse(result.getSuccess());
        assertEquals("秒杀结束时间必须晚于开始时间", result.getErrorMsg());
    }
}
