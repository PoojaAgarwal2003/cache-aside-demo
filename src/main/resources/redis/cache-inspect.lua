local kind = redis.call('TYPE', KEYS[1]).ok
if kind == 'none' then return cjson.encode({presence='ABSENT_OR_EXPIRED'}) end
if kind ~= 'string' then return cjson.encode({presence='CORRUPT'}) end
return cjson.encode({presence='PRESENT',value=redis.call('GET', KEYS[1]),ttl=redis.call('PTTL', KEYS[1])})
