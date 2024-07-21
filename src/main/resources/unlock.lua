-- 锁的key
local key = KEYS[1]
-- 线程标识
local threadId = ARGV[1]

local uuid = redis.call('get', key)

-- 比较
if (uuid == threadId) then
    -- 释放锁
    redis.call('del', key)
end
return 0