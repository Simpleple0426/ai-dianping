-- 成功/已处理的消息：先确认，再清理重试状态。
local acknowledged = redis.call('xack', KEYS[1], ARGV[1], ARGV[2])
redis.call('del', KEYS[2])
return acknowledged
