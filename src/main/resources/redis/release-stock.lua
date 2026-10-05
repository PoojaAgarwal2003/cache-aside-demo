-- Never create/increment an absent, replaced or expired counter.
-- KEYS: counter, reservation; ARGV: epoch, quantity.
local held = ARGV[1] .. ':' .. ARGV[2] .. ':HELD'
local released = ARGV[1] .. ':' .. ARGV[2] .. ':RELEASED'
local value = redis.call('GET', KEYS[2])
if not value then return 'NOT_RESERVED' end
if value == released then return 'RELEASED' end
if value ~= held then return 'CORRUPT' end
if redis.call('EXISTS', KEYS[1]) == 0 then return 'EXPIRED' end
if redis.call('HGET', KEYS[1], 'epoch') ~= ARGV[1] then return 'STALE_EPOCH' end
local ttl = redis.call('PTTL', KEYS[1])
local stock = tonumber(redis.call('HGET', KEYS[1], 'stock'))
if ttl <= 0 or not stock or stock < 0 or stock ~= math.floor(stock) then return 'CORRUPT' end
redis.call('HINCRBY', KEYS[1], 'stock', tonumber(ARGV[2]))
redis.call('SET', KEYS[2], released, 'KEEPTTL')
return 'RELEASED'
