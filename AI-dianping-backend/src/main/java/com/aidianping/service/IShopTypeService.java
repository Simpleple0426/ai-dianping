package com.aidianping.service;

import com.aidianping.dto.Result;
import com.aidianping.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryShopType();
}
