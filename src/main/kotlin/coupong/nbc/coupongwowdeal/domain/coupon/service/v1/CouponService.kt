package coupong.nbc.coupongwowdeal.domain.coupon.service.v1

import coupong.nbc.coupongwowdeal.domain.coupon.dto.CouponInfoResponse
import coupong.nbc.coupongwowdeal.domain.coupon.dto.CouponResponse
import coupong.nbc.coupongwowdeal.domain.coupon.dto.CreateCouponRequest
import coupong.nbc.coupongwowdeal.infra.security.UserPrincipal

interface CouponService {

    fun getCouponList(userPrincipal: UserPrincipal): List<CouponResponse>
    fun createCoupon(request: CreateCouponRequest): CouponInfoResponse
    fun issueCouponToUser(couponId: Long, userId: Long): CouponResponse
    fun issueCouponToUserWithPessimisticLock(couponId: Long, userId: Long): CouponResponse

    /**
     * 부하테스트 전용. 락 방식과 트랜잭션 경계를 런타임에 지정해 같은 로직을 실행한다.
     * @param lockMode  pessimistic | spin | redisson
     * @param txWrapped true 면 트랜잭션이 락을 감싼다(= 커밋 전에 락이 풀리는 버그 형태),
     *                  false 면 락이 트랜잭션을 감싼다(= 정상 형태).
     *                  pessimistic 은 락 수명이 트랜잭션과 같아 이 값의 영향을 받지 않는다.
     */
    fun issueCouponBench(couponId: Long, userId: Long, lockMode: String, txWrapped: Boolean): CouponResponse
    fun useCoupon(couponId: Long, userPrincipal: UserPrincipal)
    fun expireCoupon(couponId: Long)
    fun deleteExpiredCoupon()
}
