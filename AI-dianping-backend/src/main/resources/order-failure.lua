-- 失败记录按原 Stream 消息 ID 去重，保留完整原文供核对/恢复。
-- 记录写入失败时不会执行 XACK。Lua 执行期间其他命令不能穿插。
if redis.call('exists', KEYS[3]) == 0 then
    redis.call('hset', KEYS[3],
        'stream', KEYS[1], 'group', ARGV[1], 'messageId', ARGV[2],
        'payload', ARGV[3], 'reason', ARGV[4], 'attempts', ARGV[5],
        'failedAt', ARGV[6], 'status', 'NEEDS_REVIEW')
end
local acknowledged = redis.call('xack', KEYS[1], ARGV[1], ARGV[2])
redis.call('del', KEYS[2])
return acknowledged
