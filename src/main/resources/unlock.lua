-- 这里的 KEYS[1] 就是锁的key，这里的ARGV[1] 就是当前线程标示
local key = KEYS[1]
local threadId = ARGV[1]
-- 比较当前线程标识与锁中的标识是否一致
if (redis.call('get', key) == threadId) then
    -- 释放锁
    return redis.call('del', key)
end
return 0