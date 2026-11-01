package com.aidianping.service.impl;

import com.aidianping.dto.Result;
import com.aidianping.entity.Shop;
import com.aidianping.utils.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopServiceRegressionTest {
    private final ShopServiceImpl service = spy(new ShopServiceImpl());
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final GeoOperations<String, String> geo = mock(GeoOperations.class);
    private final CacheClient cache = mock(CacheClient.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "cacheClient", cache);
        when(redis.opsForGeo()).thenReturn(geo);
    }

    private Shop storedShop(long typeId, double x) {
        return new Shop().setId(1L).setTypeId(typeId).setX(x).setY(31.2);
    }

    @Test
    void categoryOnlyUpdateMovesExistingCoordinatesToNewCategory() {
        Shop patch = new Shop().setId(1L).setTypeId(2L);
        doReturn(storedShop(1L, 121.4), storedShop(2L, 121.4))
                .when(service).getById(1L);
        doReturn(true).when(service).updateById(patch);

        assertTrue(service.updateShop(patch).getSuccess());
        verify(geo).remove("shop:geo:1", "1");
        verify(geo).add("shop:geo:2", new Point(121.4, 31.2), "1");
        verify(cache).delete("cache:shop:1");
    }

    @Test
    void coordinateOnlyUpdateUsesStoredCategory() {
        Shop patch = new Shop().setId(1L).setX(121.5);
        doReturn(storedShop(1L, 121.4), storedShop(1L, 121.5))
                .when(service).getById(1L);
        doReturn(true).when(service).updateById(patch);

        assertTrue(service.updateShop(patch).getSuccess());
        verify(geo).add("shop:geo:1", new Point(121.5, 31.2), "1");
        verify(geo, never()).remove(anyString(), any(String[].class));
        verify(cache).delete("cache:shop:1");
    }

    @Test
    void nonexistentShopDoesNotPretendUpdateSucceeded() {
        doReturn(null).when(service).getById(1L);
        Result result = service.updateShop(new Shop().setId(1L));
        assertFalse(result.getSuccess());
        verify(service, never()).updateById(any(Shop.class));
        verifyNoInteractions(geo, cache);
    }

    @Test
    void failedDatabaseUpdateDoesNotChangeGeo() {
        Shop patch = new Shop().setId(1L).setTypeId(2L);
        doReturn(storedShop(1L, 121.4)).when(service).getById(1L);
        doReturn(false).when(service).updateById(patch);
        assertFalse(service.updateShop(patch).getSuccess());
        verifyNoInteractions(geo, cache);
    }

    @Test
    void geoFailureStillAttemptsToInvalidateOldDetailsAfterDatabaseUpdate() {
        Shop patch = new Shop().setId(1L).setX(121.5);
        doReturn(storedShop(1L, 121.4), storedShop(1L, 121.5))
                .when(service).getById(1L);
        doReturn(true).when(service).updateById(patch);
        when(geo.add("shop:geo:1", new Point(121.5, 31.2), "1"))
                .thenThrow(new IllegalStateException("GEO unavailable"));
        assertThrows(IllegalStateException.class, () -> service.updateShop(patch));
        verify(cache).delete("cache:shop:1");
    }

    @Test
    void nearbySecondPageRequestsNearestTenBeforeSkippingFive() {
        service.queryShopByType(1, 2, 121.4, 31.2);
        ArgumentCaptor<GeoSearchCommandArgs> args =
                ArgumentCaptor.forClass(GeoSearchCommandArgs.class);
        verify(geo).search(eq("shop:geo:1"), any(GeoReference.class),
                any(Distance.class), args.capture());
        assertEquals(Sort.Direction.ASC, args.getValue().getSortDirection());
        assertEquals(10L, args.getValue().getLimit());
    }
}
