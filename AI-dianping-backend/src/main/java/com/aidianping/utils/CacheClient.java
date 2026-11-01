package com.aidianping.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.hash.BloomFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * <p>
 * Redis缓存工具类
 * </p>
 *
 */
@Slf4j
@Component
public class CacheClient {

    private static final long LOCAL_CACHE_MAX_SIZE = 10_000L;
    private static final long LOCAL_CACHE_TTL_SECONDS = 300L;

    // 布隆过滤器
    @Resource
    private BloomFilter<Long> bloomFilter;

    private final Cache<String, LocalCacheValue> localCache = CacheBuilder.newBuilder()
            .maximumSize(LOCAL_CACHE_MAX_SIZE)
            .build();

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit){
        setJson(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public String get(String key, Long time, TimeUnit unit) {
        LocalCacheValue localValue = getLocalValue(key);
        if (localValue != null) {
            return localValue.value;
        }
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json != null) {
            putLocalValue(key, json, time, unit);
        }
        return json;
    }

    public void delete(String key) {
        localCache.invalidate(key);
        stringRedisTemplate.delete(key);
    }

    //根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题,利用互斥锁解决缓存击穿问题
    // 在此处 R的数据类型是Shop ID 是Long
    // <R, ID> 声明两个类型参数 R是返回值
    public <R, ID> R queryWithPassThroughAndMutex(
           final String keyPrefix, final String lockKeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
            Long time, TimeUnit unit, Long nullValueTTL, TimeUnit nullValueUnit){
        if (id == null) {
            return null;
        }
        // 1.布隆过滤器判断id是否存在
        // return false id不存在加载的数据中 说明当前商户不存在  项目启动时 会初始化BloomFilterInitializer 加载所有的商户id
        if (id instanceof Long && !bloomFilter.mightContain((Long)id)) {
            //不存在，直接返回错误
            log.info("布隆过滤器拦截，id:{}不存在", id);
            return null;
        }

        //拼接redis的key
        String key = keyPrefix + id;
        String lockKey = lockKeyPrefix + id;
        while (true) {
            // 2.先查本地缓存，再查Redis缓存
            String json = getFromLocalOrRedis(key, time, unit, nullValueTTL, nullValueUnit);

            // json有三种情况 1. null 去查数据库  2. “” 空值 上次查询就是不存在   3. 正常的商户信息

            // 3.判断是否存在  情况3 正常的商户信息
            if (StrUtil.isNotBlank(json)) {
                // 存在，直接返回
                R result = JSONUtil.toBean(json, type);  // type是Shop
                return result;
            }


            // 4.判断是否位空值（缓存空值） 情况2 不存在
            if (json != null) {
                // 是空值，返回不存在
                return null;
            }

            // 5.不存在，去数据库查找 情况1 Null值
            R result = null;  // R -> Shop类型
            boolean isLock = false;

            // 尝试获取锁
            try {
                isLock = tryLock(lockKey);
                //判断是否获取锁成功
                if (!isLock) {
                    //失败，休眠并等待50ms重试
                    Thread.sleep(50);
                    continue;
                } else {
                    // 再次检查缓存（Double Check）, 避免重复查询数据库
                    // 为什么要再检查一遍缓存  B等待锁的过程中可能别的线程A已经把结果写入缓存了
                    json = getFromLocalOrRedis(key, time, unit, nullValueTTL, nullValueUnit);

                    // 缓存中存在了
                    if (StrUtil.isNotBlank(json)) {
                        R cached = JSONUtil.toBean(json, type);
                        return cached;
                    }
                    // 缓存显示的是情况2  明确表示该商户不存在
                    if (json != null) {
                        return null;
                    }
                }

                //成功，根据id查询数据库
                result = dbFallback.apply(id);
                // 6.数据库不存在，返回错误信息
                if (result == null) {
                    //将空值写入redis（设置较短的过期时间）
                    setJson(key, "", nullValueTTL, nullValueUnit);
                    return null;
                }
                // 7.存在，写入redis
                // 生成TTL随机数,防止缓存雪崩
                Long random = (long) RandomUtil.randomInt(3, 10);
                set(key, result, time + random, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                //释放锁
                if (isLock) {
                    unlock(lockKey);
                }
            }
            // 8.返回
            return result;
        }
    }

    //获取互斥锁 key是锁的key
    // 如果锁 key 不存在，就创建它，并让它 10 秒后自动过期；如果已经存在，就创建失败。
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //释放锁 删除redis缓存中的lockkey
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


    //key  商户缓存的Key
    private String getFromLocalOrRedis(String key, Long time, TimeUnit unit, Long nullValueTTL, TimeUnit nullValueUnit) {
        // 1. 读取本地缓存
        LocalCacheValue localValue = getLocalValue(key);
        if (localValue != null) {
            return localValue.value;
        }

        // 2. 读取Redis缓存 类型是String
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.1 Redis中没有这个key
        if (json == null) {
            return null;
        }

        // 2.2 Redis有该商户的数据
        if (StrUtil.isNotBlank(json)) {
            putLocalValue(key, json, time, unit);
        }

        // 2.3 空字符串 之前查过了 该商户不存在
        else {
            putLocalValue(key, json, nullValueTTL, nullValueUnit);
        }
        return json;
    }

    private void setJson(String key, String json, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, json, time, unit);
        putLocalValue(key, json, time, unit);
    }

    private LocalCacheValue getLocalValue(String key) {
        LocalCacheValue value = localCache.getIfPresent(key);
        if (value == null) {
            return null;
        }
        if (value.isExpired()) {
            localCache.invalidate(key);
            return null;
        }
        return value;
    }

    // 把查询结果放入Java当前应用的本地缓存
    private void putLocalValue(String key, String value, Long time, TimeUnit unit) {
        // 没有有效的缓存时间 删除这个key
        if (time == null || unit == null || time <= 0) {
            localCache.invalidate(key);
            return;
        }
        // 计算本地缓存保存多久
        long ttlNanos = Math.min(unit.toNanos(time), TimeUnit.SECONDS.toNanos(LOCAL_CACHE_TTL_SECONDS));
        // 保存和设置过期时间
        localCache.put(key, new LocalCacheValue(value, System.nanoTime() + ttlNanos));
    }

    private static class LocalCacheValue {
        private final String value;
        private final long expireAtNanos;

        private LocalCacheValue(String value, long expireAtNanos) {
            this.value = value;
            this.expireAtNanos = expireAtNanos;
        }

        private boolean isExpired() {
            return System.nanoTime() - expireAtNanos >= 0;
        }
    }

}
