if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('GET', KEYS[1]) ~= ARGV[1] then
    return 'REJECTED_GENERATION'
end
if ARGV[2] ~= '' and (redis.call('TYPE', KEYS[3]).ok ~= 'string' or redis.call('GET', KEYS[3]) ~= ARGV[2]) then
    return 'REJECTED_LOCK'
end
redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[4])
redis.call('PEXPIRE', KEYS[1], ARGV[5])
return 'STORED'
