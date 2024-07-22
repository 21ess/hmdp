-- 1.参数
-- 1.1 优惠卷id
local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order" .. voucherId
-- 1.一人一单/库存
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end

if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 2.减少库存
redis.call('incrby', stockKey, -1)
-- 3.set中添加userId
redis.call('sadd', orderKey, userId)

return 0