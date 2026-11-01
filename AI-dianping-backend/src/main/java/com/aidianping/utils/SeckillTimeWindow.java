package com.aidianping.utils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/** 数据库的活动本地时间统一按北京时间解释，Redis 中保存绝对毫秒时间戳。 */
public final class SeckillTimeWindow {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private SeckillTimeWindow() {
    }

    public static Map<String, String> redisFields(LocalDateTime begin, LocalDateTime end) {
        if (begin == null || end == null) {
            throw new IllegalArgumentException("秒杀开始时间和结束时间不能为空");
        }
        if (!end.isAfter(begin)) {
            throw new IllegalArgumentException("秒杀结束时间必须晚于开始时间");
        }
        return Map.of(
                "beginTime", Long.toString(begin.atZone(BUSINESS_ZONE).toInstant().toEpochMilli()),
                "endTime", Long.toString(end.atZone(BUSINESS_ZONE).toInstant().toEpochMilli()));
    }
}
