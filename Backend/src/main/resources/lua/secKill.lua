-- 优惠券和用户ID
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
-- 数据Key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
-- 库存不足返回1
if(tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end
-- 该用户已经下过单了返回2
if(redis.call('sismember', orderKey, userId) == 1) then
    return 2
end
-- 正常扣库存+用户下单
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0