package com.jxdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Component
public class SimpleRedisLock implements ILock{
    @Resource
    StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";
    /**
     * 使用redis执行脚本时，我们需要先初始化脚本(这样能够提高性能)，所以我们使用static块来初始化脚本信息
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 请注意：ID_PREFIX是在调用tryLock方法前就已经放到了JVM常量池中的
     * 所以，同一个JVM下，ID_PREFIX都是一样的，但是线程ID是不一样的
     * 不同JVM下，虽然线程ID可能一样，但是常量池中的ID_PREFIX是不一样的，通过这样就解决了线程唯一标识的问题
     */
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    @Override
    public boolean tryLock(String name, long timeoutSec) {
        /**
         * 锁里面的value如何取值呢？
         * 可以存储线程的唯一id，这样我们就可以很清楚的知道是哪个线程获得了这把锁
         *
         * 这样加锁会造成一个问题：误删问题(还是会造成一人多单的问题)，就是线程1把线程2的锁给删除了
         * 造成的原因：就是在执行业务的时候发生了阻塞,锁自动释放了
         * 解决方法：删除锁之前，判断这把锁是否是自己的？
         * 因为我们存到value是线程的唯一标识，所以这个方法是可行的
         * 但是，不同的JVM可能线程的唯一标识是一样的，所以需要进行改进
         * 在threadId前面再拼接一个UUID即可
         */
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return success != null ? success : false;
    }

    @Override
    public void unLock(String name) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        // 获取锁中的标识
        // String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断要释放的锁是否是自己的锁
        // if (threadId.equals(id)){
        //     //是的话，通过del删除锁
        //     stringRedisTemplate.delete(KEY_PREFIX + name);
        // }
        // 不是的话，就不删除

        /**
         * 这样会有一个极端的情况，线程1判断成功进行释放锁，但是释放锁的过程中阻塞了，
         * 线程2获取锁，这时候线程1阻塞结束就还是会删除线程2的锁，还是会造成误删问题
         * 造成的原因：判断和删锁，不具备原子性，导致判断和删锁中间是有间隔的，就有可能会导致阻塞
         * 解决方法：添加原子性，但是redis的原子性很难实现，
         * 所以我们可以使用Lua脚本语言，让java同步执行Lua脚本语言中的多条Redis命令，实现原子性
         */
        // 调用Lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name), threadId);
        // 通过调用Lua脚本，我们就能够实现 拿锁比锁删锁的原子性动作了~

    }
}
