package com.jxdp.service;

import com.jxdp.dto.Result;
import com.jxdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopTypeService extends IService<ShopType> {
    /**
     * 查询商铺列表(缓存)
     * @return
     */
    Result queryTypeList();
}
