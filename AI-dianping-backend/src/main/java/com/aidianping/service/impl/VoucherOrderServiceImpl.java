package com.aidianping.service.impl;

import com.aidianping.dto.Result;
import com.aidianping.entity.VoucherOrder;
import com.aidianping.mapper.VoucherOrderMapper;
import com.aidianping.service.ISeckillVoucherService;
import com.aidianping.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aidianping.utils.RedisIdWorker;
import com.aidianping.utils.UserHolder;
import com.aidianping.utils.SeckillTimeWindow;
import com.aidianping.utils.RedisConstants;
import com.aidianping.service.order.OrderMessageProcessor;
import com.aidianping.service.order.OrderProcessingResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.time.Clock;
import java.util.Objects;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private TransactionTemplate transactionTemplate;

    private final AtomicBoolean running = new AtomicBoolean(true);

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 使用单个线程的线程池来异步执行任务，保证任务的顺序性
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        preloadSeckillStock();
        initializeOrderStreamGroup();
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /** 只初始化缺失库存，避免重启时用尚未消费订单的数据库库存覆盖 Redis 预扣结果。 */
    private void preloadSeckillStock() {
        try {
            seckillVoucherService.list().forEach(voucher -> {
                if (voucher.getVoucherId() != null && voucher.getStock() != null) {
                    String timeKey = RedisConstants.SECKILL_TIME_KEY + voucher.getVoucherId();
                    Map<String, String> window;
                    try {
                        window = SeckillTimeWindow.redisFields(voucher.getBeginTime(), voucher.getEndTime());
                    } catch (IllegalArgumentException invalid) {
                        stringRedisTemplate.delete(timeKey);
                        log.warn("秒杀时间配置无效，禁止抢购，voucherId={}", voucher.getVoucherId());
                        return;
                    }
                    stringRedisTemplate.opsForHash().putAll(timeKey, window);
                    stringRedisTemplate.opsForValue().setIfAbsent(
                            com.aidianping.utils.RedisConstants.SECKILL_STOCK_KEY + voucher.getVoucherId(),
                            voucher.getStock().toString());
                }
            });
        } catch (Exception e) {
            log.warn("秒杀库存预热失败，后续新增秒杀券仍会写入 Redis", e);
        }
    }

    private void initializeOrderStreamGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup("stream.orders", ReadOffset.from("0"), "g1");
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("BUSYGROUP")) {
                return;
            }
            try {
                // Redis Stream 不存在时先写入一个可安全忽略的引导消息，再创建消费组。
                stringRedisTemplate.opsForStream().add(
                        StreamRecords.newRecord().in("stream.orders").ofMap(Map.of("type", "bootstrap")));
                stringRedisTemplate.opsForStream().createGroup("stream.orders", ReadOffset.from("0"), "g1");
            } catch (Exception retryException) {
                if (!(retryException.getMessage() != null && retryException.getMessage().contains("BUSYGROUP"))) {
                    log.warn("订单 Stream 消费组初始化失败，消费者将继续重试", retryException);
                }
            }
        }
    }

    @PreDestroy
    private void onDestroy() {
        running.set(false);
        SECKILL_ORDER_EXECUTOR.shutdownNow();
    }

    // 单个逻辑消费者 c1：每轮先恢复一批 Pending，再读取新消息。
    // Pending 使用游标有界扫描，暂缓或失败留档不可用的消息不会饿死后续消息。
    private class VoucherOrderHandler implements Runnable {
        private final OrderMessageProcessor processor = new OrderMessageProcessor(
                stringRedisTemplate, VoucherOrderServiceImpl.this::createVoucherOrder, Clock.systemUTC());
        private String pendingCursor = "0";

        @Override
        public void run() {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    handlePendingList();
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed()));
                    if (list != null && running.get()) {
                        for (MapRecord<String, Object, Object> record : list) {
                            handleRecord(record);
                        }
                    }
                } catch (Exception e) {
                    log.error("读取订单队列失败，稍后重试", e);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private void handlePendingList() {
            List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                    Consumer.from("g1", "c1"), StreamReadOptions.empty().count(20),
                    StreamOffset.create("stream.orders", ReadOffset.from(pendingCursor)));
            if (list == null || list.isEmpty()) {
                pendingCursor = "0";
                return;
            }
            for (MapRecord<String, Object, Object> record : list) {
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                pendingCursor = record.getId().getValue();
                handleRecord(record);
            }
        }

        private void handleRecord(MapRecord<String, Object, Object> record) {
            try {
                processor.process(record);
            } catch (Exception e) {
                // Redis/失败留档/ACK 不可用时保留 Pending，由后续扫描恢复。
                log.error("订单消息尚未确认，messageId={}", record.getId(), e);
            }
        }
    }

    /** 在同一个数据库事务内完成幂等校验、库存扣减和订单写入。 */
    public OrderProcessingResult createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        if (userId == null || voucherOrder.getVoucherId() == null || voucherOrder.getId() == null) {
            return OrderProcessingResult.INVALID_MESSAGE;
        }

        // 使用分布式锁确保用户操作的幂等性
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            return OrderProcessingResult.RETRY_LATER;
        }

        try {
            OrderProcessingResult result = transactionTemplate.execute(status -> {
                // 再次查询订单，作为 Lua 之外的数据库幂等兜底。
                VoucherOrder existingById = getById(voucherOrder.getId());
                if (existingById != null) {
                    return Objects.equals(existingById.getUserId(), userId)
                            && Objects.equals(existingById.getVoucherId(), voucherOrder.getVoucherId())
                            ? OrderProcessingResult.ALREADY_EXISTS : OrderProcessingResult.DUPLICATE_CONFLICT;
                }
                VoucherOrder existing = query().eq("user_id", userId)
                        .eq("voucher_id", voucherOrder.getVoucherId()).one();
                if (existing != null) {
                    // 另一订单ID不能冒充本消息的成功订单，留档核对。
                    return OrderProcessingResult.DUPLICATE_CONFLICT;
                }

                // 扣除mysql数据库里面的stock stock - 1
                boolean success = seckillVoucherService.update()
                        .setSql("stock = stock - 1")
                        .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                        .update();

                if (!success) {
                    return OrderProcessingResult.OUT_OF_STOCK;
                }
                boolean saved = save(voucherOrder);
                if (!saved) {
                    status.setRollbackOnly();
                }
                return saved ? OrderProcessingResult.CREATED : OrderProcessingResult.RETRY_LATER;
            });
            return result == null ? OrderProcessingResult.RETRY_LATER : result;
        } finally {
            if (redisLock.isHeldByCurrentThread()) {
                redisLock.unlock();
            }
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        if (voucherId == null || voucherId <= 0 || UserHolder.getUser() == null) {
            return Result.fail("请先登录并选择有效的优惠券");
        }


        Long userId = UserHolder.getUser().getId();
        //  生成订单ID
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        if (result == null) {
            return Result.fail("秒杀服务暂不可用，请稍后重试");
        }
        return switch (result.intValue()) {
            case 0 -> Result.ok(orderId);
            case 1 -> Result.fail("库存不足");
            case 2 -> Result.fail("不能重复下单");
            case 3 -> Result.fail("秒杀尚未开始");
            case 4 -> Result.fail("秒杀已经结束");
            case 5 -> Result.fail("秒杀活动时间未配置或无效");
            default -> Result.fail("秒杀服务暂不可用，请稍后重试");
        };
    }
}
