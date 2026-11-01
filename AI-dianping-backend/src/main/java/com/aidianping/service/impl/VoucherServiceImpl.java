package com.aidianping.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aidianping.dto.Result;
import com.aidianping.entity.Voucher;
import com.aidianping.mapper.VoucherMapper;
import com.aidianping.entity.SeckillVoucher;
import com.aidianping.service.ISeckillVoucherService;
import com.aidianping.service.IVoucherService;
import com.aidianping.utils.RedisConstants;
import com.aidianping.utils.SeckillTimeWindow;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private VoucherMapper voucherMapper;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = voucherMapper.queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional // 遇到失败或者异常时回滚
    public void addSeckillVoucher(Voucher voucher) {
        if (voucher == null || voucher.getStock() == null || voucher.getStock() <= 0) {
            throw new IllegalArgumentException("秒杀库存必须大于0");
        }
        Map<String, String> timeWindow = SeckillTimeWindow.redisFields(
                voucher.getBeginTime(), voucher.getEndTime());
        // 保存优惠券
        if (!save(voucher) || voucher.getId() == null) {
            throw new IllegalStateException("优惠券保存失败");
        }
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("秒杀信息保存失败");
        }

        // 先配置活动窗口，Lua 缺少有效时间时拒绝受理。
        stringRedisTemplate.opsForHash().putAll(
                RedisConstants.SECKILL_TIME_KEY + voucher.getId(), timeWindow);
        stringRedisTemplate.opsForValue().set(RedisConstants.SECKILL_STOCK_KEY + voucher.getId(),
                voucher.getStock().toString());
    }
}
