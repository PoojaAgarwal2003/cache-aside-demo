local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'none' and kind ~= 'string' then return 'CORRUPT' end
redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2])
local generation = redis.call('GET', KEYS[1])
if not generation or #generation ~= 36 or redis.call('PTTL', KEYS[1]) <= 0 then return 'CORRUPT' end
return generation
