if redis.call('EXISTS', KEYS[1]) == 0 then return 'ABSENT' end
local epoch = redis.call('HGET', KEYS[1], 'epoch')
local stock = redis.call('HGET', KEYS[1], 'stock')
if not epoch or not stock then return 'CORRUPT' end
return epoch .. ':' .. stock .. ':' .. redis.call('PTTL', KEYS[1])
