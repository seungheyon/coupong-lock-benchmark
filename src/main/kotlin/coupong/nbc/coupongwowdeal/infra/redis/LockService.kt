package coupong.nbc.coupongwowdeal.infra.redis

import org.redisson.api.RedissonClient
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Service
class LockService(
    private val redisLockRepository: RedisLockRepository,
    private val redissonClient: RedissonClient,
) {
    /**
     * Redisson 분산 락. dev-redisson 브랜치의 구현을 Lettuce 경로와 공존하도록 옮겨온 것.
     * leaseTime 을 주지 않으므로 watchdog(기본 30초 연장)이 동작한다.
     */
    fun <T> tryLock(key: String, tryTime: Long, timeUnit: TimeUnit, action: () -> T): T? {
        val rLock = redissonClient.getLock(key)
        return if (rLock.tryLock(tryTime, timeUnit)) action() else null
    }

    /**
     * 원본(localtest 작업분)은 finally 에서 무조건 unlock() 을 호출했는데, 락을 못 잡은 스레드가
     * 그대로 호출하면 IllegalMonitorStateException 이 난다. 측정 중 예외가 지연 통계를 오염시키므로
     * 보유 여부를 확인하고 푼다.
     */
    fun rUnlock(key: String) {
        val rLock = redissonClient.getLock(key)
        if (rLock.isHeldByCurrentThread) rLock.unlock()
    }

    fun executeWithLock(key: String, timeout: Long, action: () -> Unit): Boolean {
        if (redisLockRepository.lock(key = key, timeout = timeout)) {
            try {
                action()
                return true
            } catch (e: Exception) {
                unlock(key)
                return false
            }
        } else {
            return false
        }
    }

    fun <T> spinUntilLockAcquired(key: String, timeout: Long, action: () -> T): T? {
        var lockResult = false

        var result: T? = null

        while (!lockResult) {
            Thread.sleep(Random.nextLong(15, 30))
            lockResult = if (redisLockRepository.lock(key = key, timeout = timeout)) {
                result = action()
                true
            } else {
                false
            }
        }

        return result
    }

    fun unlock(key: String, value: String = "") {
        redisLockRepository.unlock(key = key, value = value)
    }
}