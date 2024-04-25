package com.jxdp.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.jxdp.dto.Result;
import com.jxdp.entity.ShopType;
import com.jxdp.mapper.ShopTypeMapper;
import com.jxdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jxdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    StringRedisTemplate stringRedisTemplate;

    /**
     * 查询商铺列表(缓存)
     * @return
     */
    @Override
    public Result queryTypeList() {
        // 第一步：查询缓存中是否有商铺类型列表
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        List<String> cacheShopTypeList = stringRedisTemplate.opsForList().range(key, 0, -1);

        if (!cacheShopTypeList.isEmpty()){
            // 若有，使用stream API将List<String>转换成List<ShopType>并返回
            List<ShopType> shopTypeList = cacheShopTypeList.stream().
                    map(shopType -> JSONObject.parseObject(shopType, ShopType.class)).
                    collect(Collectors.toList());
            return Result.ok(shopTypeList);
        }
        // 缓存中没有商铺列表，从数据库中查询
        List<ShopType> shopTypeList = this.query().orderByAsc("sort").list();
        // 查到以后将该商铺列表(首先转换成字符串列表)存到redis中
        List<String> list = shopTypeList.stream().
                            map(shopType -> JSONObject.toJSONString(shopType)).
                            collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(key, list);
        return Result.ok(shopTypeList);
    }
}
