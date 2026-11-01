package com.aidianping.mapper;

import com.aidianping.entity.ShopType;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 */
public interface ShopTypeMapper extends BaseMapper<ShopType> {


    @Select("select * from tb_shop_type")
    List<ShopType> selectAll();
}
