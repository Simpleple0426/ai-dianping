package com.aidianping.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.aidianping.entity.SeckillVoucher;
import com.aidianping.entity.VoucherOrder;
import com.aidianping.service.ISeckillVoucherService;
import com.aidianping.service.order.OrderProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoucherOrderProcessingTest {
    private final VoucherOrderServiceImpl service = spy(new VoucherOrderServiceImpl());
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final ISeckillVoucherService stockService = mock(ISeckillVoucherService.class);
    private final QueryChainWrapper<VoucherOrder> orders = mock(QueryChainWrapper.class, RETURNS_SELF);
    private final UpdateChainWrapper<SeckillVoucher> stock = mock(UpdateChainWrapper.class, RETURNS_SELF);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final VoucherOrder order = new VoucherOrder().setId(99L).setUserId(1001L).setVoucherId(10L);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "redissonClient", redisson);
        ReflectionTestUtils.setField(service, "seckillVoucherService", stockService);
        ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(manager));
        when(manager.getTransaction(any())).thenReturn(status);
        when(redisson.getLock("lock:order:1001")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doReturn(null).when(service).getById(99L);
        doReturn(orders).when(service).query();
        when(orders.eq(anyString(), any())).thenReturn(orders);
        when(stockService.update()).thenReturn(stock);
        when(stock.setSql(anyString(), any(Object[].class))).thenReturn(stock);
        when(stock.eq(anyString(), any())).thenReturn(stock);
        when(stock.gt(anyString(), any())).thenReturn(stock);
        when(stock.update()).thenReturn(true);
        doReturn(true).when(service).save(any(VoucherOrder.class));
    }

    @Test
    void lockContentionIsRetryableRatherThanDuplicateSuccess() {
        when(lock.tryLock()).thenReturn(false);
        assertEquals(OrderProcessingResult.RETRY_LATER, service.createVoucherOrder(order));
        verifyNoInteractions(manager, stockService);
    }

    @Test
    void sameOrderReplayDoesNotDeductStockAgain() {
        doReturn(order).when(service).getById(99L);
        assertEquals(OrderProcessingResult.ALREADY_EXISTS, service.createVoucherOrder(order));
        verifyNoInteractions(stockService);
        verify(service, never()).save(any());
    }

    @Test
    void differentOrderIdForSameUserAndVoucherIsAConflict() {
        when(orders.one()).thenReturn(new VoucherOrder().setId(98L).setUserId(1001L).setVoucherId(10L));
        assertEquals(OrderProcessingResult.DUPLICATE_CONFLICT, service.createVoucherOrder(order));
        verifyNoInteractions(stockService);
    }

    @Test
    void exhaustedDatabaseStockIsExplicitFailure() {
        when(stock.update()).thenReturn(false);
        assertEquals(OrderProcessingResult.OUT_OF_STOCK, service.createVoucherOrder(order));
        verify(service, never()).save(any());
    }

    @Test
    void failedSaveMarksStockTransactionForRollbackAndRetry() {
        doReturn(false).when(service).save(order);
        assertEquals(OrderProcessingResult.RETRY_LATER, service.createVoucherOrder(order));
        verify(status).setRollbackOnly();
        verify(lock).unlock();
    }

    @Test
    void saveExceptionRollsBackTransactionAndReleasesLock() {
        doThrow(new IllegalStateException("insert failed")).when(service).save(order);
        assertThrows(IllegalStateException.class, () -> service.createVoucherOrder(order));
        verify(manager).rollback(status);
        verify(lock).unlock();
    }

    @Test
    void successfulSaveCommitsBeforeReturningCreated() {
        assertEquals(OrderProcessingResult.CREATED, service.createVoucherOrder(order));
        verify(manager).commit(status);
        verify(lock).unlock();
    }
}
