-- KEYS: counter, unique reservation; ARGV: epoch, quantity, metadata TTL ms.
local previous = redis.call('GET', KEYS[2])
local held = ARGV[1] .. ':' .. ARGV[2] .. ':HELD'
if previous then
    if previous == held then return 'HELD' end
    return 'ALREADY_FINISHED'
end
if redis.call('EXISTS', KEYS[1]) == 0 then return 'MISSING_COUNTER' end
if redis.call('HGET', KEYS[1], 'epoch') ~= ARGV[1] then return 'STALE_EPOCH' end
local ttl = redis.call('PTTL', KEYS[1])
local stock = tonumber(redis.call('HGET', KEYS[1], 'stock'))
if ttl <= 0 or not stock or stock < 0 or stock ~= math.floor(stock) then return 'CORRUPT' end
if stock < tonumber(ARGV[2]) then return 'REJECTED' end
redis.call('HINCRBY', KEYS[1], 'stock', -tonumber(ARGV[2]))
redis.call('SET', KEYS[2], held, 'PX', ARGV[3])
return 'RESERVED'
