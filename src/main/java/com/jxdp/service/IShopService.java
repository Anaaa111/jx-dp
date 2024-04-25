package com.jxdp.service;

import com.jxdp.dto.Result;
import com.jxdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {
    /**
     * 根据商铺id查询商铺信息(使用缓存)
     * @param id
     * @return
     */
    Result queryById(Long id);

    /**
     * 更新店铺，保证一致性
     * @param shop
     */
    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
