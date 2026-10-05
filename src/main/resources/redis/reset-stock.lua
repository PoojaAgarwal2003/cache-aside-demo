-- Caller has drained local buyers and locked the authoritative product row.
redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'epoch', ARGV[1], 'stock', ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return 'RESET'
