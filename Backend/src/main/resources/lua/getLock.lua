local key = KEYS[1]
local threadId = ARGV[1]
local releaseTime = ARGV[2]
-- 判断锁是否已经存在
if(redis.call('exists', key) == 0) then
    -- 如果不存在就设置锁，并刷新过期时间
    redis.call('hset', key, threadId, '1')
    redis.call('expire', key, releaseTime)
    return 1
end
-- 如果锁存在了，判断锁的标识是否是自己
if(redis.call('hexist', key, threadId) == 1) then
    -- 如果是自己，就让锁的计数器+1并刷新有效期
    redis.call('hincrby', key, threadId, '1')
    redis.call('expire', key, releaseTime)
    return 1
end
return 0 -- 获取锁失败