package coupong.nbc.coupongwowdeal.domain.coupon.service.v1

import coupong.nbc.coupongwowdeal.domain.common.aop.Lock
import coupong.nbc.coupongwowdeal.domain.common.aop.Transactional
import coupong.nbc.coupongwowdeal.domain.coupon.dto.CouponInfoResponse
import coupong.nbc.coupongwowdeal.domain.coupon.dto.CouponResponse
import coupong.nbc.coupongwowdeal.domain.coupon.dto.CreateCouponRequest
import coupong.nbc.coupongwowdeal.domain.coupon.repository.v1.CouponRepository
import coupong.nbc.coupongwowdeal.domain.user.repository.v1.UserRepository
import coupong.nbc.coupongwowdeal.exception.AccessDeniedException
import coupong.nbc.coupongwowdeal.exception.EmptyQuantityException
import coupong.nbc.coupongwowdeal.exception.ModelNotFoundException
import coupong.nbc.coupongwowdeal.infra.security.UserPrincipal
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class CouponServiceImpl(
    private val couponRepository: CouponRepository,
    private val userRepository: UserRepository,
) : CouponService {
    override fun getCouponList(userPrincipal: UserPrincipal): List<CouponResponse> {
        val userId = userPrincipal.id
        return couponRepository.findCouponUserListByUserId(userId)
            .map { CouponResponse.toResponse(it) }
    }

    override fun createCoupon(request: CreateCouponRequest): CouponInfoResponse {
        return request.toCoupon()
            .let { couponRepository.save(it) }
            .let { CouponInfoResponse.toResponse(it) }
    }

    override fun issueCouponToUser(couponId: Long, userId: Long) =
        Lock.spin("LOCK:COUPON:$couponId", 3000) {
            Transactional {
                check(!couponRepository.isCouponIssued(couponId, userId)) {
                    throw IllegalStateException("User already issue coupon")
                }

                val user = userRepository.findByIdOrNull(userId) ?: throw ModelNotFoundException("user", userId)
                val coupon =
                    couponRepository.findCouponById(couponId) ?: throw ModelNotFoundException("coupon", couponId)
                check(coupon.hasQuantity()) { throw EmptyQuantityException() }

                couponRepository.issueCouponToUser(coupon, user)
                    .also { coupon.decreaseQuantity() }
                    .let { CouponResponse.toResponse(it) }
            }
        } as CouponResponse

    override fun issueCouponToUserWithPessimisticLock(couponId: Long, userId: Long) =
        Transactional {
            check(!couponRepository.isCouponIssued(couponId, userId)) {
                throw IllegalStateException("User already issue coupon")
            }

            val user = userRepository.findByIdOrNull(userId) ?: throw ModelNotFoundException("user", userId)
            val coupon =
                couponRepository.findOptionalCouponById(couponId).orElseThrow()
                    ?: throw ModelNotFoundException("coupon", couponId)
            check(coupon.hasQuantity()) { throw EmptyQuantityException() }

            couponRepository.issueCouponToUser(coupon, user)
                .also { coupon.decreaseQuantity() }
                .let { CouponResponse.toResponse(it) }
        }

    /**
     * 부하테스트 전용 진입점. issueCouponToUser / issueCouponToUserWithPessimisticLock 과
     * 동일한 발급 로직을 쓰되, 락 방식과 트랜잭션 경계만 파라미터로 바꾼다.
     */
    override fun issueCouponBench(
        couponId: Long,
        userId: Long,
        lockMode: String,
        txWrapped: Boolean
    ): CouponResponse {
        val key = "LOCK:COUPON:$couponId"
        val body: () -> CouponResponse = { issueBody(couponId, userId, pessimistic = lockMode == "pessimistic") }

        return when (lockMode) {
            // DB 비관적 락: 락 획득이 조회 쿼리(FOR UPDATE) 안에서 일어나고 커밋과 함께 풀린다.
            // 트랜잭션보다 좁게 잠그는 상태를 만들 수 없으므로 txWrapped 의 영향을 받지 않는다.
            "pessimistic" -> Transactional { body() }

            "spin" ->
                if (txWrapped) Transactional { Lock.spin(key, LOCK_TTL_MS) { body() }!! }
                else Lock.spin(key, LOCK_TTL_MS) { Transactional { body() } }!!

            "redisson" ->
                if (txWrapped) Transactional { Lock.rLock(key, RLOCK_WAIT_SEC, TimeUnit.SECONDS) { body() }!! }
                else Lock.rLock(key, RLOCK_WAIT_SEC, TimeUnit.SECONDS) { Transactional { body() } }!!

            else -> throw IllegalArgumentException("unknown lockMode: $lockMode")
        }
    }

    private fun issueBody(couponId: Long, userId: Long, pessimistic: Boolean): CouponResponse {
        check(!couponRepository.isCouponIssued(couponId, userId)) {
            throw IllegalStateException("User already issue coupon")
        }

        val user = userRepository.findByIdOrNull(userId) ?: throw ModelNotFoundException("user", userId)
        val coupon =
            if (pessimistic) couponRepository.findOptionalCouponById(couponId).orElseThrow()
            else couponRepository.findCouponById(couponId) ?: throw ModelNotFoundException("coupon", couponId)
        check(coupon.hasQuantity()) { throw EmptyQuantityException() }

        return couponRepository.issueCouponToUser(coupon, user)
            .also { coupon.decreaseQuantity() }
            .let { CouponResponse.toResponse(it) }
    }

    companion object {
        /** Lettuce 스핀락 키 TTL(ms). 원본 CouponServiceImpl 의 Lock.spin(key, 3000) 과 동일. */
        private const val LOCK_TTL_MS = 3000L

        /** Redisson tryLock 대기 시간(초). 원본 dev 브랜치의 Lock.rLock(key, 3000, SECONDS) 와 동일. */
        private const val RLOCK_WAIT_SEC = 3000L
    }

    override fun useCoupon(couponId: Long, userPrincipal: UserPrincipal) {
        Transactional {
            val userId = userPrincipal.id
            val requestUser = (userRepository.findByIdOrNull(userId)
                ?: throw ModelNotFoundException("User", userId))

            couponRepository.findCouponUserByCouponId(couponId, userId)
                ?.also { check(it.user.id == requestUser.id) { throw AccessDeniedException("no permission") } }
                ?.also { check(!it.isExpired()) { throw IllegalStateException("Coupon is expired") } }
                ?.also { it.use() }
                ?: throw ModelNotFoundException("CouponUser", couponId)
        }
    }

    override fun expireCoupon(couponId: Long) = Transactional {
        couponRepository.couponUserDelete(couponId)
        couponRepository.couponDelete(couponId)
    }

    override fun deleteExpiredCoupon() {
        Lock.standard("scheduled_task_lock", 600000L) {
            Transactional { couponRepository.deleteExpiredCoupon() }
        }
    }
}