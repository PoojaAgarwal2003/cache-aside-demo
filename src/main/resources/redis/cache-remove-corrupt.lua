if redis.call('TYPE', KEYS[1]).ok == 'string' and redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 'REMOVED'
end
return 'CHANGED'
