package com.jxdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 基于string类型的攻击类(解决缓存穿透和缓存击穿问题)
     */

    /**
     * 设置缓存数据，并设置过期时间，用于缓存穿透问题
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithString(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONObject.toJSONString(value), time, unit);
    }

    /**
     * 设置缓存数据，并设置逻辑过期时间，用于缓存击穿问题(逻辑过期的解决方案)
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithStringWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONObject.toJSONString(redisData));
    }
    /**
     * 基于字符串的缓存穿透问题
     */
    public <R, ID> R queryWithStringWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                                      Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)){
            // 缓存中存在，则直接返回
            return JSON.toJavaObject(JSON.parseObject(json), type);
        }
        // 判断命中的是否为空值
        if (json != null) {
            // 表明从缓存中获取的数据是空字符串，表明数据库和缓存中都没有这个数据，直接返回错误信息
            // 返回一个错误信息
            return null;
        }
        // 不存在，进行缓存重建
        // 根据id查询数据库
        R r = dbFallback.apply(id);
        // 数据库中没有，则往缓存中存入空值
        if (r == null){
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
        }
        // 数据库中存在， 则将数据库中的数据写入到缓存中
        this.setWithString(key, r, time, unit);
        return r;
    }

    /**
     * 基于字符串的缓存击穿问题
     * 解决方法：逻辑过期
     */
    public <R, ID> R queryWithStringWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                                    Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)){
            // 未命中或者命中了但是是空值，直接返回null
            return null;
        }
        // 命中，需要先把json反序列化为对象，获取到缓存数据和过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((cn.hutool.json.JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 已过期，需要重建缓存
        // 1.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock){
            // 重建缓存
            // 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    // 模拟重建缓存数据的延迟(就是缓存重建复杂)
                    Thread.sleep(200);
                    // 重建缓存
                    this.setWithStringWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 不管有没有获得锁，都直接返回旧数据
        return r;
    }
    /**
     * 基于字符串的缓存击穿问题
     * 解决方法：互斥锁
     */
    public <R, ID> R queryWithStringWithMutex(String keyPrefix, ID id, Class<R> type,
                                                      Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 命中，1.不为空值直接返回，2.为空值则返回错误信息(表明缓存和数据库中都没有)
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }
        // 为命中，实现缓存重建
        // 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        R r = null;
        try {
            // 缓存重建
            boolean isLock = tryLock(lockKey);
            if (!isLock){
                // 获取锁失败，则休眠并重试
                Thread.sleep(50);
                return queryWithStringWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 获取锁成功，根据id查询数据库
            r = dbFallback.apply(id);
            // 模拟重建缓存数据的延迟(就是缓存重建复杂)
            Thread.sleep(200);
            if (r == null){
                // 数据库中没有，保存空值，解决缓存穿透问题
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 6.存在，写入redis
            this.setWithString(key, r, time, unit);
        }catch (Exception e){
            throw new RuntimeException(e);
        }finally {
            // 释放锁
            unlock(lockKey);
        }
        return r;
    }
    /**
     * 基于map类型的攻击类(解决缓存穿透和缓存击穿问题)
     */

    /**
     * 设置缓存数据，并设置过期时间，用于缓存穿透问题
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithMap(String key, Object value, Long time, TimeUnit unit){
        // 首先将value转换成map
        Map<String, Object> map = this.beanToMap(value);
        // 将map存入缓存，并设置过期时间
        stringRedisTemplate.opsForHash().putAll(key, map);
        stringRedisTemplate.expire(key,time, unit);
    }

    /**
     * 设置缓存数据，并设置逻辑过期时间，用于缓存击穿问题(逻辑过期的解决方案)
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithMapWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        // 首先将value转换成map
        Map<String, Object> map = this.beanToMap(value);
        // 然后，将过期时间添加到map中
        LocalDateTime expireTime = LocalDateTime.now().plusSeconds(unit.toSeconds(time));
        map.put("expireTime", expireTime.toString());
        // 3. 写入redis中
        stringRedisTemplate.opsForHash().putAll(key, map);
    }

    /**
     * 基于map的缓存击穿问题
     */
    public <R, ID> R queryWithMapWithPassThrough(String keyPrefix, ID id, R r, Class<R> type,
                                                    Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);
        R cacheObject = null;
        // 判断map是否为null
        if (!map.isEmpty() && !"".equals(map.get("id"))){
            // map不为空，且里面的id字段不是空字符串(如果是的话就表明缓存和数据中都没有这个数据，就可以直接返回错误信息了)
            cacheObject = BeanUtil.fillBeanWithMap(map, r, false);
            return cacheObject;
        }
        // 判断命中的是否为空值
        if ("".equals(map.get("id"))){
            return null;
        }
        // 若缓存中没有，则从数据库中查询(进行缓存重建)
        // 根据id查询数据库
        r = dbFallback.apply(id);
        // 数据库中没有，则往缓存中存入空值
        if (r == null){
            stringRedisTemplate.opsForHash().put(key, "id","");
            stringRedisTemplate.expire(key, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 数据库中存在， 则将数据库中的数据写入到缓存中
        this.setWithMap(key, r, time, unit);
        return r;
    }

    /**
     * 基于map的缓存穿透问题
     * 解决方法：逻辑过期
     */
    public <R, ID> R queryWithMapWithLogicalExpire(String keyPrefix, ID id, R r, Class<R> type,
                                                      Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);
        if (map.isEmpty() || "".equals(map.get("id"))){
            // 未命中或者命中了但是是空值，直接返回null
            return null;
        }
        // 命中，判断该缓存数据是否过期
        // 将map转化为R对象，以及从map中获取过期时间
        r = BeanUtil.fillBeanWithMap(map, r, false);
        LocalDateTime expireTime = LocalDateTime.parse((String) map.get("expireTime"));

        // 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 已过期，需要重建缓存
        // 1.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock){
            // 重建缓存
            // 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithMapWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 不管有没有获得锁，都直接返回旧数据
        return r;
    }
    /**
     * 基于map的缓存穿透问题
     * 解决方法：互斥锁
     */
    public <R, ID> R queryWithMapWithMutex(String keyPrefix, ID id, R r, Class<R> type,
                                    Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);
        R cacheObject = null;
        // 命中，1.不为空值直接返回，2.为空值则返回错误信息(表明缓存和数据库中都没有)
        if (!map.isEmpty() && !"".equals(map.get("id"))) {
            // 3.存在，直接返回
            cacheObject = BeanUtil.fillBeanWithMap(map, r, false);
            return cacheObject;
        }
        // 判断命中的是否是空值
        if ("".equals(map.get("id"))){
            // 返回一个错误信息
            return null;
        }
        // 未命中，实现缓存重建
        // 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock){
                // 获取锁失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMapWithMutex(keyPrefix, id, r, type, dbFallback, time, unit);
            }
            // 获取锁成功，根据id查询数据库
            // 检查redis缓存是否存在
            map = stringRedisTemplate.opsForHash().entries(key);
            // 判断缓存中是否有数据
            if (!map.isEmpty() && !"".equals(map.get("id"))){
                // 若是缓存中有该商铺信息，则直接返回
                // 将map转换成Bean返回给前端
                cacheObject = BeanUtil.fillBeanWithMap(map, r, false);
                return cacheObject;
            }
            // 缓存重建
            r = dbFallback.apply(id);
            if (r == null){
                // 数据库中没有，保存空值，解决缓存穿透问题
                stringRedisTemplate.opsForHash().put(key, "id","");
                stringRedisTemplate.expire(key, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 6.存在，写入redis
            this.setWithMap(key, r, time, unit);
        }catch (Exception e){
            throw new RuntimeException(e);
        }finally {
            // 释放锁
            unlock(lockKey);
        }
        return r;
    }


    /**
     * 通用方法
     */

    /**
     * 获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 将Object转换成map
     */
    private Map<String, Object> beanToMap(Object value){
        Map<String, Object> map = BeanUtil.beanToMap(value, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName,fieldValue) -> {
                    if (fieldValue == null){
                        fieldValue = "";
                    }else {
                        fieldValue = fieldValue.toString();
                    }
                    return fieldValue;
                }));
        return map;
    }
}
