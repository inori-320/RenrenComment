local key = KEYS[1]
local threadId = ARGV[1]
local releaseTime = ARGV[2]
-- 判断锁是否还是被自己持有
if(redis.call('hexists', key, threadId) == 0) then
    -- 锁已经不是自己的了，直接返回
    return nil
end
-- 是自己的锁，重入次数-1
local count = redis.call('hincrby', key, threadId, -1)
if(count > 0) then
    -- 大于0就说明不能释放锁
    redis.call('expire', key, releaseTime)
else
    -- 最后一个释放锁的，直接删除锁
    redis.call('del', key);
end
return nil