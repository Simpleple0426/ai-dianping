package com.aidianping.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.hash.BloomFilter;
import com.aidianping.dto.Result;
import com.aidianping.utils.CacheClient;
import com.aidianping.entity.Shop;
import com.aidianping.mapper.ShopMapper;
import com.aidianping.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aidianping.utils.RedisConstants;
import com.aidianping.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.Resource;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    // 布隆过滤器
    @Resource
    private BloomFilter<Long> bloomFilter;

    // 根据ID查询店铺
    /*
    参数解释
    RedisConstants.CACHE_SHOP_KEY redis缓存的key额度前缀
    RedisConstants.LOCK_SHOP_KEY redis锁的key的前缀
    id  shop的id
    SHop.class  缓存中的JSON转换成什么类型
    this::getById  必要时如何从数据库获取数据
    RedisConstants.CACHE_SHOP_TTL shop的缓存时间
    RedisConstants.CACHE_NULL_TTL 不存在时，null值缓存多久
    */
    @Override
    public Result queryById(Long id) {
        Shop shop = cacheClient.queryWithPassThroughAndMutex(
                RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY,
                id, Shop.class, this::getById,
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES,
                RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES
        );
        if(shop==null){
            return Result.fail("店铺不存在");
        }else{
            return Result.ok(shop);
        }

    }


    @Override
    //先更新数据库，再删除缓存
    public Result updateShop(Shop shop) {
        if (shop == null || shop.getId() == null || shop.getId() <= 0) {
            return Result.fail("店铺id必须大于0");
        }
        Long shopId = shop.getId();
        Shop oldShop = getById(shopId);
        if (oldShop == null) {
            return Result.fail("店铺不存在");
        }
        // 1.更新数据库
        if (!updateById(shop)) {
            return Result.fail("店铺更新失败");
        }
        try {
            // 请求可能只包含分类或坐标，使用数据库更新后的完整资料维护 GEO。
            Shop updatedShop = getById(shopId);
            if (updatedShop == null) {
                return Result.fail("店铺更新后不存在");
            }
            if (oldShop.getTypeId() != null
                    && !oldShop.getTypeId().equals(updatedShop.getTypeId())) {
                stringRedisTemplate.opsForGeo().remove(
                        RedisConstants.SHOP_GEO_KEY + oldShop.getTypeId(), shopId.toString());
            }
            syncGeo(updatedShop);
            return Result.ok();
        } finally {
            // 数据库已经更新，即使 GEO 同步异常，也要尝试清理旧的详情缓存。
            cacheClient.delete(RedisConstants.CACHE_SHOP_KEY + shopId);
        }
    }

    // 新增店铺时需要更新布隆过滤器
    @Override
    @Transactional
    public boolean save(Shop shop) {
        boolean result = super.save(shop);
        if (result && shop.getId() != null) {
            // 将新增的店铺ID添加到布隆过滤器
            bloomFilter.put(shop.getId());
            syncGeo(shop);
        }
        return result;
    }

    private void syncGeo(Shop shop) {
        // typeId  商户的类别 x、y 经纬度 id 标识是哪家商户

        if (shop.getTypeId() == null || shop.getX() == null || shop.getY() == null || shop.getId() == null) {
            return;
        }

        stringRedisTemplate.opsForGeo().add(
                RedisConstants.SHOP_GEO_KEY + shop.getTypeId(),
                new org.springframework.data.geo.Point(shop.getX(), shop.getY()),
                shop.getId().toString());
    }


    @Override
    // current  第几页
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (typeId == null || typeId <= 0) {
            return Result.fail("店铺类型不能为空");
        }
        int page = current == null || current < 1 ? 1 : current;

        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> dbPage = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(page, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(dbPage.getRecords());
        }

        // 2.计算分页参数  逻辑上从redis去前end条 再跳过from前面的
        int from = (page - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = page * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        /*
        eoReference.fromCoordinate(x, y), 用户的坐标
        new Distance(5, Metrics.KILOMETERS),  查询半径为5公里
        includeDistance().limit(end)  返回距离 最多end条
        results 多个result
        result content + distance content是商户信息 name是商户ID
         */
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5, Metrics.KILOMETERS),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                .includeDistance().sortAscending().limit(end)
                );

        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }

        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        // ids 商户的Id

        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list(); //按照ID列表的排序
        // 把距离也放进去
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }


}
