local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '(' .. now, '+inf')
local count = redis.call('ZCARD', KEYS[1])
if count >= limit then
    local first = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
    local remainingMs = tonumber(first[2]) + window - now
    return cjson.encode({outcome='REJECTED',remaining=0,retryAfterSeconds=math.max(1,math.ceil(remainingMs/1000))})
end
redis.call('ZADD', KEYS[1], now, ARGV[3])
redis.call('PEXPIRE', KEYS[1], window)
return cjson.encode({outcome='ALLOWED',remaining=limit-count-1,retryAfterSeconds=0})
