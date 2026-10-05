if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 'ACQUIRED' end
return 'BUSY'
