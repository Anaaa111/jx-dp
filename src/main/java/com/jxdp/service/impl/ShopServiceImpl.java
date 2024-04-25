package com.jxdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jxdp.dto.Result;
import com.jxdp.entity.Shop;
import com.jxdp.mapper.ShopMapper;
import com.jxdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jxdp.utils.CacheClient;
import com.jxdp.utils.RedisConstants;
import com.jxdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Resource
    CacheClient cacheClient;
    // 构建一个线程池，独立线程可以从这里面取
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 根据商铺id查询商铺信息(使用缓存)
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 缓存穿透问题解决
        // Shop shop = queryWithPassThrough(id);
        // Shop shop = cacheClient.queryWithMapWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id,
        //         new Shop(), Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 缓存击穿问题(互斥锁)
        // Shop shop = queryWithMutex(id);
        Shop shop = cacheClient.queryWithMapWithMutex(RedisConstants.CACHE_SHOP_KEY, id,
                new Shop(), Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 缓存击穿问题(逻辑删除)
        // Shop shop = queryWithLogicalExpire(id);
        // Shop shop = cacheClient.queryWithMapWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id,
        //         new Shop(), Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (shop == null){
            return Result.fail("商铺不存在！！");
        }
        return Result.ok(shop);
    }
    /**
     * 将缓存穿透问题的解决思路封装起来，方便后面观看
     */
    // public Shop queryWithPassThrough(Long id){
    //     // 1. 从redis查询缓存
    //     String key = RedisConstants.CACHE_SHOP_KEY + id;
    //     Map<Object, Object> shopMap = stringRedisTemplate.opsForHash().entries(key);
    //     if (!shopMap.isEmpty() && !"".equals(shopMap.get("id"))){
    //         // 若是缓存中有该商铺信息，则直接返回
    //         // 将map转换成Bean返回给前端
    //         Shop cacheShop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
    //         return cacheShop;
    //     }
    //     if ("".equals(shopMap.get("id"))){
    //         return null;
    //     }
    //     // 若缓存中没有，则从数据库中查询
    //     Shop shop = this.getById(id);
    //     if (shop == null){
    //         stringRedisTemplate.opsForHash().put(key, "id","");
    //         stringRedisTemplate.expire(key, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    //         return null;
    //     }
    //     // 然后将该该店铺转换成成map存储到redis中
    //     Map<String, Object> map = BeanUtil.beanToMap(shop, new HashMap<>(), CopyOptions.create()
    //             .setIgnoreNullValue(true)
    //             .setFieldValueEditor((fieldName,fieldValue) -> {
    //                 if (fieldValue == null){
    //                     fieldValue = "";
    //                 }else {
    //                     fieldValue = fieldValue.toString();
    //                 }
    //                 return fieldValue;
    //             }));
    //     stringRedisTemplate.opsForHash().putAll(key, map);
    //     // 设置过期时间，当作缓存数据库保持一致性的兜底方案
    //     stringRedisTemplate.expire(key,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
    //     // 最后将商铺返回给前端
    //     return shop;
    // }

    /**
     * 将缓存击穿问题的解决思路封装起来，方便后面观看
     * 解决思路：互斥锁
     */
    // public Shop queryWithMutex(Long id){
    //     // 1. 从redis查询缓存
    //     String key = RedisConstants.CACHE_SHOP_KEY + id;
    //     Shop cacheShop = null;
    //     Map<Object, Object> shopMap = stringRedisTemplate.opsForHash().entries(key);
    //     // 判断缓存中是否有数据
    //     if (!shopMap.isEmpty() && !"".equals(shopMap.get("id"))){
    //         // 若是缓存中有该商铺信息，则直接返回
    //         // 将map转换成Bean返回给前端
    //         cacheShop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
    //         return cacheShop;
    //     }
    //     // 表示数据库和缓存中都没有，直接返回不要再去查数据库了
    //     if ("".equals(shopMap.get("id"))){
    //         return null;
    //     }
    //     // 若缓存中没有(未命中)，则从数据库中查询
    //     // 未命中：1.1. 尝试获取互斥锁
    //     String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
    //     Shop shop = null;
    //     try {
    //         boolean flag = tryLock(lockKey);
    //         // 1.2 判断是否获取成功
    //         if (!flag){
    //             // 1.3. 互斥锁获取失败，表明有人在重建缓存，故休眠一段时间再次进行查询缓存
    //             Thread.sleep(50);
    //             return queryWithMutex(id);
    //         }
    //         // 1.4 互斥锁获取成功，就查询数据库，重建缓存信息
    //         // 注意：获取锁成功以后，应该还要再次检测redis缓存是否存在
    //         // 如果线程1刚好释放锁，线程2刚好去获取锁，那么线程2就又会去执行一次重建操作，但是这是没有必要的，线程2是可以直接从缓存中获取数据
    //         // 检查redis缓存是否存在
    //         shopMap = stringRedisTemplate.opsForHash().entries(key);
    //         // 判断缓存中是否有数据
    //         if (!shopMap.isEmpty() && !"".equals(shopMap.get("id"))){
    //             // 若是缓存中有该商铺信息，则直接返回
    //             // 将map转换成Bean返回给前端
    //             cacheShop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
    //             return cacheShop;
    //         }
    //         // 检查完以后再去查数据库进行缓存重建
    //         shop = this.getById(id);
    //         // 模拟重建缓存数据的延迟(就是缓存重建复杂)
    //         Thread.sleep(200);
    //         if (shop == null){
    //             stringRedisTemplate.opsForHash().put(key, "id","");
    //             stringRedisTemplate.expire(key, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    //             return null;
    //         }
    //         // 然后将该该店铺转换成成map存储到redis中
    //         Map<String, Object> map = BeanUtil.beanToMap(shop, new HashMap<>(), CopyOptions.create()
    //                 .setIgnoreNullValue(true)
    //                 .setFieldValueEditor((fieldName,fieldValue) -> {
    //                     if (fieldValue == null){
    //                         fieldValue = "";
    //                     }else {
    //                         fieldValue = fieldValue.toString();
    //                     }
    //                     return fieldValue;
    //                 }));
    //         // 将查询到的数据库信息存放到缓存中
    //         stringRedisTemplate.opsForHash().putAll(key, map);
    //         // 设置过期时间，当作缓存数据库保持一致性的兜底方案
    //         stringRedisTemplate.expire(key,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
    //     }catch (Exception e){
    //         throw new RuntimeException(e);
    //     }finally {
    //         // 1.5 释放互斥锁
    //         unLock(lockKey);
    //     }
    //     // 最后将商铺返回给前端
    //     return shop;
    // }
    /**
     * 将缓存击穿问题的解决思路封装起来，方便后面观看
     * 解决思路：逻辑过期
     */
    // public Shop queryWithLogicalExpire(Long id){
    //     // 1. 从redis查询缓存
    //     // 注意：逻辑过期是不存在缓存未命中的情况的
    //     String key = RedisConstants.CACHE_SHOP_KEY + id;
    //     Map<Object, Object> shopMap = stringRedisTemplate.opsForHash().entries(key);
    //     // 为了安全，还是判断一下未命中
    //     if (shopMap.isEmpty() || "".equals(shopMap.get("id"))){
    //         return null;
    //     }
    //     // 命中，判断该缓存数据是否过期
    //     // 将shopMap转化为shop对象，以及从shopMap中获取过期时间
    //     Shop shop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
    //     LocalDateTime expireTime = LocalDateTime.parse((String) shopMap.get("expireTime"));
    //     if (expireTime.isAfter(LocalDateTime.now())){
    //         // 缓存没有过期，直接返回商铺信息
    //         return shop;
    //     }
    //     // 缓存过期了，获取互斥锁，尝试进行缓存重建操作
    //     String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
    //     boolean flag = tryLock(lockKey);
    //     // 判断是否获取锁成功
    //     if (flag){
    //         // 获取到了互斥锁，开启独立线程进行缓存重建过程
    //         CACHE_REBUILD_EXECUTOR.submit(() -> {
    //             // 缓存重建
    //             try {
    //                 this.saveShop2Redis(id, 20L);
    //             }catch (Exception e){
    //                 throw new RuntimeException(e);
    //             }finally {
    //                 unLock(lockKey);
    //             }
    //         });
    //     }
    //     // 不管有没有获取到互斥锁，都将旧的商铺信息返回
    //     return shop;
    // }

    /**
     * 更新店铺，保证一致性
     * @param shop
     */
    @Transactional
    public Result update(Shop shop) {
        // 首先判断该店铺是否存在
        Long id = shop.getId();
        if (id == null){
            return Result.fail("该商铺不存在！！");
        }
        // 保证一致性：首先更新数据库，然后在删除缓存，能够最大成都保证一致性
        // 跟新数据库
        this.updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否需要根据坐标查询
        if (x == null || y == null){
            // 不需要坐标查询，根据类型分页查询
            Page<Shop> page = this.query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 需要根据坐标查询
        // 由于我们是根据redis进行分页查询，没有框架帮我们计算起始条目和结束条目的索引，所以我们需要根据current自己计算
        // 2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 3. 查询redis，根据距离排序、分页. 结果: shopId, distance
        // 注意：geo无法物理分页，只能逻辑分页，只能搜索出0-end的数据，from-end的数据需要自己手动截取出来
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        // redis6.2才能使用search命令
        // GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
        //         key,
        //         GeoReference.fromCoordinate(x, y),
        //         new Distance(5000),
        //         RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        // );
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(
                key,
                new Circle(new Point(x, y), new Distance(100000)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end));
        // 4. 解析出id
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        // 4.1 截取from-end的部分
        // 注意：若是分页过后没有数据，则直接跳过from条数据，后面是没有数据的，ids也就为null了，后面数据库的操作就会报错
        // 所以，这里我们需要判断一下跳过from条数据后是否还有数据
        if (list.size() <= from){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result ->{
            // 4.2 获取商铺id
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            // 4.3 获取距离
            Distance distance = result.getDistance();
            // 距离应该该商铺id一一对应
            distanceMap.put(shopId, distance);
        });
        // 5. 根据id查询shop(按照id的顺序)
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = this.query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        // 将对于商铺的距离添加进去
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }


    /**
     * 获取互斥锁
     * 1.使用redis中的setnx方法，若redis无这个key，则插入成功，返回1，否则返回0
     * 2. 恰好满足互斥锁的互斥性
     */
    /* private boolean tryLock(String key){
        // 为了防止出现意外导致一直没有释放锁，导致后续的线程一直处于等待状态，给互斥锁加上一个TTL
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // flag可能为null
        return flag != null ? flag : false;
    } */
    /**
     * 释放互斥锁: 就是删除setnx
     */
    // private void unLock(String key){
    //     stringRedisTemplate.delete(key);
    // }
    /**
     * 利用单元测试及逆行缓存预热
     */
    // public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
    //     // 查询店铺数据
    //     Shop shop = this.getById(id);
    //     // 模拟重建缓存数据的延迟(就是缓存重建复杂)
    //     Thread.sleep(200);
    //     // 写入redis中
    //     // 1.1 将redisData转换成map
    //     Map<String, Object> map = BeanUtil.beanToMap(shop, new HashMap<>(), CopyOptions.create()
    //             .setIgnoreNullValue(true)
    //             .setFieldValueEditor((fieldName,fieldValue) -> {
    //                 if (fieldValue == null){
    //                     fieldValue = "";
    //                 }else {
    //                     fieldValue = fieldValue.toString();
    //                 }
    //                 return fieldValue;
    //             }));
    //     // 封装过期时间到map中
    //     LocalDateTime expireTime = LocalDateTime.now().plusSeconds(expireSeconds);
    //     map.put("expireTime", expireTime.toString());
    //     // 3. 写入redis中
    //     stringRedisTemplate.opsForHash().putAll(RedisConstants.CACHE_SHOP_KEY + id, map);
    // }
}
