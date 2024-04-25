package com.jxdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.jxdp.dto.Result;
import com.jxdp.entity.VoucherOrder;
import com.jxdp.mapper.VoucherOrderMapper;
import com.jxdp.service.ISeckillVoucherService;
import com.jxdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jxdp.service.IVoucherService;
import com.jxdp.utils.RedisIdWorker;
import com.jxdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    IVoucherService voucherService;
    @Resource
    ISeckillVoucherService seckillVoucherService;
    @Resource
    RedisIdWorker redisIdWorker;
    @Resource
    StringRedisTemplate stringRedisTemplate;

    /**
     * 使用Redisson就不需要自己注入分布式锁对象了，注入Redisson即可
     */
    @Resource
    RedissonClient redissonClient;
    // @Resource
    // ILock lock;

    /**
     * 异步线程任务设置好了以后，那么该如何取执行呢？
     * 使用springboot中的@PostConstruct注解
     * 该注解可以使得标注的方法在类初始化完以后就执行，
     * 而从阻塞队列中取下单信息去创建订单，项目启动后就随时可能要执行的，
     * 所以我们就需要在类初始化完以后就去执行哪个异步线程任务
     */
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private static final String BUSINESS_NAME = "lock:order:";
    // 代理对象
    private IVoucherOrderService proxy;

    // 异步处理线程池(就是一个单线程)
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 线程任务，用于线程池处理的任务(从消息队列中去消息进行下单)
    private class VoucherOrderHandler implements Runnable{
        String queenName = "stream.orders";
        // 从阻塞队列中取下单信息进行下单
        @Override
        public void run() {
            while (true){
                try {
                    // 1. 获取消息队列中订单信息
                    //  XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    // 其中String是这条消息的id
                    // Object, Object 订单信息，以键值对的形式存储
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queenName, ReadOffset.lastConsumed())
                    );
                    // 判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        // 2.1 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 2.2 获取成功，则可以下单
                    // 2.2.1 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    // 将value转化成voucherOrder类
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 下单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queenName,"g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }
        private void handlePendingList() {
            while (true){
                try {
                    // 1. 获取pendingList中未处理的订单信息(处理异常的订单信息)
                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queenName, ReadOffset.from("0"))
                    );
                    // 判断消息获取是否成功
                    if (list == null || list.isEmpty()){
                        // 2.1 如果获取失败，说明pending-list中没有异常消息，直接结束循环
                        break;
                    }
                    // 2.2 获取成功，则可以下单
                    // 2.2.1 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    // 将value转化成voucherOrder类
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 下单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queenName,"g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    // // 阻塞队列
    // private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    // // 线程任务，用于线程池处理的任务(阻塞队列中去消息)
    // private class VoucherOrderHandler implements Runnable{
    //     // 从阻塞队列中取下单信息进行下单
    //     @Override
    //     public void run() {
    //         while (true){
    //             try {
    //                 // 1. 从阻塞队列中取出订单信息
    //                 VoucherOrder voucherOrder = orderTasks.take();
    //                 // 2. 创建订单
    //                 handleVoucherOrder(voucherOrder);
    //             } catch (InterruptedException e) {
    //                 log.error("处理订单异常", e);
    //             }
    //         }
    //     }
    // }

    // 初始化lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    /**
     * 秒杀下单
     * 遇到的问题：
     * 1. 超卖问题
     *    解决方法. 悲观锁：使得所有线程都串行执行
     *            乐观锁: 版本号法和CAS法
     * @param voucherId
     * @return
     */
    // @Override
    // public Result seckilloucher(Long voucherId) {
    //     // 查询优惠卷信息
    //     SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
    //     // 判断优惠卷是否开始
    //     if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
    //         return Result.fail("秒杀还未开始");
    //     }
    //     // 判断优惠卷是否结束
    //     if (voucher.getEndTime().isBefore(LocalDateTime.now())){
    //         return Result.fail("秒杀已经结束");
    //     }
    //     // 判断库存是否充足
    //     Integer stock = voucher.getStock();
    //     if (stock < 1){
    //         return Result.fail("库存不足");
    //     }
    //     /**
    //      * 一人一单问题
    //      * 遇到的问题：还是会出现一人多单的问题，原理跟超卖问题相似
    //      * 解决方法：给它加锁，但是乐观锁比较适合更新数据，而现在的主要核心是插入数据，所以我们使用悲观锁
    //      * 将下面的逻辑封装成一个方法，给该方法加一个悲观锁，让其串行执行该方法
    //      */
    //     // Long userId = UserHolder.getUser().getId();
    //     // synchronized (userId.toString().intern()){
    //     //     IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    //     //     return proxy.createVoucherOrder(voucherId);
    //     // }
    //
    //     /**
    //      * 使用synchronized锁会造成在分布式环境下的一人多单问题，
    //      * 因为分布式环境有多个JVM，就会有多个锁，多个JVM都能够获取到锁的
    //      * 解决方法：基于redis构建一个互斥锁(就是在集群外部构建一个锁，所有JVM都使用这个锁)
    //      * 注意：在这里使用userID当作key，是因为相同业务，同一个用户构建一把锁就可以了，这样保证了性能的同时也解决了一人一单的问题
    //      */
    //     Long userId = UserHolder.getUser().getId();
    //     String key = BUSINESS_NAME + userId;
    //     // 通过Redisson创建锁对象
    //     RLock lock = redissonClient.getLock(BUSINESS_NAME + userId);
    //     boolean isLock = lock.tryLock();
    //     if (!isLock){
    //         // 获取锁失败，表明已经有线程进行下单的业务了，直接返回错误信息
    //         return Result.fail("不允许重复下单！！");
    //     }
    //     // 获取锁成功，调用下单业务
    //     try {
    //         IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    //         return proxy.createVoucherOrder(voucherId);
    //     }catch (Exception e){
    //         throw new RuntimeException(e);
    //     }finally {
    //         // 释放锁
    //         lock.unlock();
    //     }
    // }

    /**
     * 优化基于阻塞队列的优化秒杀下单
     * 基于阻塞队列有以下缺点：
     * 1.存在内存限制问题(JVM内存限制)
     * 2.存在数据安全问题，若从阻塞队列中取出下单信息进行业务处理，该业务失败，这条下单信息也就丢失了
     * 优化：使用redis中的基于stream的消息队列替代阻塞队列
     * @param voucherId
     * @return
     */
    @Override
    public Result seckilloucher(Long voucherId) {
        // 执行lua脚本判断该用户是否有购买资格
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 获取订单id：有一个小缺点就是就算没有购买资格也会先生成一个订单id，这就导致在redis中无法判断真正的订单数量
        long orderId = redisIdWorker.nextId("order");
        int result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        ).intValue();
        // 判断是否有购买资格
        if (result != 0){
            // 没有购买资格
            return Result.fail(result == 1 ? "库存不足！" : "不能重复下单");
        }
        /**
         * 注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
         * 所有我们在主线程中先获取到代理对象，然后在别的线程(即异步下单的线程)那道这个代理对象调用方法，这样才能开启事务
         */
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单id
        return Result.ok(orderId);
    }

    /**
     * 优化秒杀下单(都是串行执行，处理请求时间过长)
     * 将判断购买资格和下单分开，请求中只判断是否有购买资格(有购买资格直接生成一个订单id直接返回)
     * 若有购买资格则将下单业务放入阻塞队列，利用独立线程异步下单
     * @param voucherId
     * @return
     */
    // @Override
    // public Result seckilloucher(Long voucherId) {
    //     // 执行lua脚本判断该用户是否有购买资格
    //     // 获取用户
    //     Long userId = UserHolder.getUser().getId();
    //     int result = stringRedisTemplate.execute(
    //             SECKILL_SCRIPT,
    //             Collections.emptyList(),
    //             voucherId.toString(), userId.toString()
    //     ).intValue();
    //     // 判断是否有购买资格
    //     if (result != 0){
    //         // 没有购买资格
    //         return Result.fail(result == 1 ? "库存不足！" : "不能重复下单");
    //     }
    //     // 有购买资格,保存到阻塞队列中，开启独立线程去阻塞队列中取数据进行下单即可，
    //     // 这个请求就直接返回下单成功的结果，下单的过程就跟这个请求没有关系了
    //     /**
    //      * 异步下单处理流程
    //      * 1.构建线程池，构建线程执行任务(就是从阻塞队列中取出下单信息进行下单)
    //      * 2. 每次有请求有购买资格，将把订单信息放到阻塞队列中
    //      */
    //     VoucherOrder voucherOrder = new VoucherOrder();
    //     // 2.3.订单id
    //     long orderId = redisIdWorker.nextId("order");
    //     voucherOrder.setId(orderId);
    //     // 2.4.用户id
    //     voucherOrder.setUserId(userId);
    //     // 2.5.代金券id
    //     voucherOrder.setVoucherId(voucherId);
    //     // 将订单信息保存到阻塞队列
    //     orderTasks.add(voucherOrder);
    //     /**
    //      * 注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
    //      * 所有我们在主线程中先获取到代理对象，然后在别的线程(即异步下单的线程)那道这个代理对象调用方法，这样才能开启事务
    //      */
    //     proxy = (IVoucherOrderService) AopContext.currentProxy();
    //     // 返回订单id
    //     return Result.ok(orderId);
    // }

    /**
     * 异步下单的创建订单函数(其实就是下单过程，将阻塞队列中的下单信息存放到数据库中)
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户
        // 注意：这里就不能从LocalThread里面获取用户id了，因为已经不是同一个线程了
        Long userId = voucherOrder.getUserId();
        // 创建锁对象(就是根据用户id创建锁对象防止同一个用户下多单(防止一人多单的问题))
        // 注意：这里不加锁也没事，因为我们前面已经通过lua的原子性操作解决了一人一单的问题
        RLock redisLock = redissonClient.getLock(BUSINESS_NAME + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 4.判断是否获得锁成功
        if (!isLock){
            // 获取锁失败，直接返回失败或者重试
            // 注意：这里就不需要返回给前端了，因为前端的请求已经处理完了，这是异步下单的过程
            log.error("不允许重复下单！");
            return;
        }
        try {
            // 通过代理对象调用下单操作(就是将下单信息存放到数据库中)
            proxy.createVoucherOrder2(voucherOrder);
        }finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    // @Transactional
    // public Result createVoucherOrder (Long voucherId){
    //     // 根据用户id和优惠卷id查询订单数量
    //     Long userId = UserHolder.getUser().getId();
    //     Integer count = this.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
    //     if (count > 0){
    //         // 表明用户已经买个这个优惠卷了，不能在购买了
    //         return Result.fail("用户已经购买过一次了！！");
    //     }
    //     // 扣减库存
    //     /**
    //      * 乐观锁解决超卖问题(CAS)：
    //      * 在修改的时候判断库存是否与之前的一致，若是一致则表明没有别的线程修改，若不一致则表明有别的线程提前与你修改了库存
    //      * 问题：失败率过高
    //      * 解决方法：判断库存是否大于0即可，如果大于0我随便你减库存，但是当减到0的时候，后面的线程就全部失败
    //      */
    //     boolean success = seckillVoucherService.update()
    //             .setSql("stock = stock - 1")
    //             .eq("voucher_id", voucherId)
    //             .gt("stock", 0)
    //             .update();
    //     if (!success){
    //         return Result.fail("库存不足");
    //     }
    //     // 创建订单
    //     VoucherOrder voucherOrder = new VoucherOrder();
    //     // 订单id
    //     voucherOrder.setId(redisIdWorker.nextId("order"));
    //     // 用户id
    //     voucherOrder.setUserId(userId);
    //     // 代金卷id
    //     voucherOrder.setVoucherId(voucherId);
    //     this.save(voucherOrder);
    //     return Result.ok(voucherOrder.getId());
    // }

    @Transactional
    public void createVoucherOrder2(VoucherOrder voucherOrder){
        // 根据用户id和优惠卷id查询订单数量
        // 这里也是异步线程，也不能使用threadLocal
        Long userId = voucherOrder.getUserId();
        // 由于前面用例lua脚本，这里我感觉也是没有必要再去判断了，不过为了以防万一还是可以判断一下
        Integer count = this.query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0){
            // 用户已经购买过了
            log.error("用户已经购买过了");
            return ;
        }
        // 扣减库存
        /**
         * 乐观锁解决超卖问题(CAS)：
         * 在修改的时候判断库存是否与之前的一致，若是一致则表明没有别的线程修改，若不一致则表明有别的线程提前与你修改了库存
         * 问题：失败率过高
         * 解决方法：判断库存是否大于0即可，如果大于0我随便你减库存，但是当减到0的时候，后面的线程就全部失败
         */
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success){
            // 用户已经购买过了
            log.error("用户已经购买过了");
            return ;
        }
        this.save(voucherOrder);
    }
}
