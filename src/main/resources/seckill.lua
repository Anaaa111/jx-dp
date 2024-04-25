-- 参数列表
   -- 1.1 优惠卷id
local voucherId = ARGV[1]
    -- 1.2 用户id
local userId = ARGV[2]
    -- 1.3 订单id
local orderId = ARGV[3]
-- 数据key
    -- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId
-- 脚本业务
-- 判断库存是否充足
if(tonumber(redis.call('get', stockKey)) <= 0) then
    -- 3.2.库存不足，返回1
    return 1
end
-- 判断用户是否下单
-- 使用set类型(保证了唯一性)，每次下单就将下单用户id存到set中，
-- 只有判断set中是否有该用户id，就能判断该用户是否有重复下单
if redis.call('sismember', orderKey, userId) == 1 then
    -- 重复下单
    return 2
end
-- 可以下单，则扣库存，将该用户id放入下单列表set中
redis.call("incrby", stockKey, -1)
-- 3.5.下单（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 3.6 使用redis中的基于stream的消息队列存储下单信息，所以在这里判断有购买资格以后，直接往消息队列中发送下单信息
-- 为了跟订单实体类匹配，我们直接给orderID设置与实体类属性相同
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
