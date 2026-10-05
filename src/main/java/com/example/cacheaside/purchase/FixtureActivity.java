package com.example.cacheaside.purchase;

import com.example.cacheaside.web.ApiException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Bounded single-instance drain guard; stripe collisions conservatively reject work. */
@Component
public class FixtureActivity {
    private final ReentrantReadWriteLock[] locks = new ReentrantReadWriteLock[256];

    public FixtureActivity() {
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantReadWriteLock();
        }
    }

    public <T> T purchase(long productId, Supplier<T> work) {
        return guarded(lock(productId).readLock(), work);
    }

    public <T> T maintenance(long productId, Supplier<T> work) {
        return guarded(lock(productId).writeLock(), work);
    }

    private ReentrantReadWriteLock lock(long id) {
        return locks[Math.floorMod(Long.hashCode(id), locks.length)];
    }

    private <T> T guarded(Lock lock, Supplier<T> work) {
        if (!lock.tryLock()) {
            throw new ApiException(HttpStatus.CONFLICT, "FIXTURE_BUSY",
                    "Drain in-flight work before resetting/editing the fixture; its local guard is busy.", true);
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
