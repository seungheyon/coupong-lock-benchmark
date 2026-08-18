package coupong.nbc.coupongwowdeal

import coupong.nbc.coupongwowdeal.domain.coupon.model.v1.Coupon
import coupong.nbc.coupongwowdeal.domain.coupon.repository.v1.coupon.CouponJpaRepository
import coupong.nbc.coupongwowdeal.domain.coupon.repository.v1.couponuser.CouponUserJpaRepository
import coupong.nbc.coupongwowdeal.domain.coupon.service.v1.CouponService
import coupong.nbc.coupongwowdeal.domain.user.model.v1.User
import coupong.nbc.coupongwowdeal.domain.user.repository.v1.UserRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime
import java.util.concurrent.Executors

/**
 * 트랜잭션 경계가 어긋났을 때 재고가 얼마나 새는지를, 조건을 한 번에 하나씩만 바꿔 가며 측정한다.
 *
 * 배경: 2024-07 당시 테스트에서는 오차가 1~3% 수준이었는데, 나중에 HTTP 부하테스트로 다시 재보니
 * 요청의 절반 가까이가 어긋났다. 같은 현상인데 규모가 두 자릿수 배 차이가 나서, 어떤 조건이
 * 그 차이를 만들었는지 가른다.
 *
 * 가설: 재고가 새는 창은 "락 해제 → 커밋" 구간이다. 이 창이 다음 스레드가 락을 잡고 재고를 읽는
 * 시점보다 넓으면 샌다. 따라서 (1) 커밋이 느려지거나 (2) 다음 스레드가 빨리 들어오면 더 많이 샌다.
 *
 * 실험 설계(한 번에 한 변수):
 *   C: H2 인메모리 + Dispatchers.IO      ← 2024년 조건
 *   A: MySQL(fsync)  + Dispatchers.IO    ← C에서 DB만 교체 (커밋 비용 변수)
 *   B: MySQL(fsync)  + 스레드 1000개      ← A에서 동시성만 올림 (핸드오프 변수)
 *
 * 어느 조건에서 돌릴지는 spring.datasource 설정과 아래 dispatcher 선택으로 정한다.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class BoundaryLeakTest @Autowired constructor(
    private val couponService: CouponService,
    private val couponJpaRepository: CouponJpaRepository,
    private val couponUserJpaRepository: CouponUserJpaRepository,
    private val userRepository: UserRepository,
) {

    /** 2024년 테스트와 동일: 유저 1,000명 / 재고 500개 */
    private val userSize = 1000
    private val quantity = 500

    @Test
    fun `C - 2024 조건 - Dispatchers_IO`() = run("C", Dispatchers.IO, "Dispatchers.IO(병렬도 상한)")

    @Test
    fun `B - 동시성 상한 제거 - 전용 스레드풀 1000`() {
        val pool = Executors.newFixedThreadPool(userSize)
        try {
            run("B", pool.asCoroutineDispatcher(), "전용 스레드풀 ${userSize}개")
        } finally {
            pool.shutdown()
        }
    }

    private fun run(label: String, dispatcher: CoroutineDispatcher, dispatcherDesc: String) = runBlocking {
        reset()
        val userIds = saveTestData(userSize, quantity)
        val couponId = couponJpaRepository.findAll().first().id!!

        var success = 0
        var failure = 0
        val errors = mutableMapOf<String, Int>()
        val started = System.currentTimeMillis()

        val jobs = List(userSize) { index ->
            CoroutineScope(dispatcher).async {
                try {
                    // txWrapped = true : 트랜잭션이 락을 감싼다(= 커밋 전에 락이 풀리는 버그 형태)
                    couponService.issueCouponBench(couponId, userIds[index], "spin", true)
                    synchronized(this@BoundaryLeakTest) { success++ }
                } catch (e: Exception) {
                    synchronized(this@BoundaryLeakTest) {
                        failure++
                        val k = e::class.simpleName + ": " + (e.message ?: "").take(80)
                        errors[k] = (errors[k] ?: 0) + 1
                    }
                }
            }
        }
        jobs.joinAll()
        val elapsed = System.currentTimeMillis() - started

        val issuedRows = couponUserJpaRepository.findAll().size
        val remaining = couponJpaRepository.findByIdOrNull(couponId)?.currentQuantity ?: -1
        val consumed = quantity - remaining
        val mismatch = issuedRows - consumed

        println(
            """

            ==================== 실험 $label ====================
            디스패처        : $dispatcherDesc
            유저/재고       : ${userSize}명 / ${quantity}개
            소요            : ${elapsed}ms
            성공/실패       : $success / $failure
            발급 행 수      : $issuedRows
            남은 재고       : $remaining  (차감 $consumed)
            재고 불일치     : $mismatch   (= 발급 행 수 - 실제 차감)
            불일치 비율     : ${"%.2f".format(mismatch * 100.0 / quantity)}% (재고 대비)
            예외             : ${errors.entries.sortedByDescending { it.value }.take(3).joinToString(" | ") { "${it.value}x ${it.key}" }}
            ====================================================

            """.trimIndent()
        )
    }

    private fun reset() {
        couponUserJpaRepository.deleteAll()
        couponJpaRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun saveTestData(testUserSize: Int, testQuantity: Int): List<Long> {
        val ids = (1..testUserSize).map { i ->
            userRepository.saveAndFlush(User(username = "leak$i-${System.nanoTime()}", password = "test")).id!!
        }
        couponJpaRepository.saveAndFlush(
            Coupon(
                name = "test",
                expirationAt = LocalDateTime.of(2030, 1, 1, 0, 0),
                totalQuantity = testQuantity,
                currentQuantity = testQuantity,
                discountPrice = 10000
            )
        )
        return ids
    }
}
