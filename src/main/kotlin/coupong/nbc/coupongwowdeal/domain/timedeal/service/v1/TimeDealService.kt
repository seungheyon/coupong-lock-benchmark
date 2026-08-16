package coupong.nbc.coupongwowdeal.domain.timedeal.service.v1

import coupong.nbc.coupongwowdeal.domain.timedeal.dto.request.CreateTimeDealRequest
import coupong.nbc.coupongwowdeal.domain.timedeal.dto.request.UpdateTimeDealRequest
import coupong.nbc.coupongwowdeal.domain.timedeal.dto.response.TimeDealCouponResponse
import coupong.nbc.coupongwowdeal.domain.timedeal.dto.response.TimeDealResponse
import coupong.nbc.coupongwowdeal.infra.security.UserPrincipal

interface TimeDealService {
    fun createTimeDeal(userPrincipal: UserPrincipal, request: CreateTimeDealRequest): TimeDealResponse
    fun getTimeDeals(): List<TimeDealResponse>
    fun updateTimeDeal(timeDealId: Long, timeDealUpdate: UpdateTimeDealRequest): TimeDealResponse
    fun deleteTimeDeal(timeDealId: Long)
    fun issueCoupon(userPrincipal: UserPrincipal, timeDealId: Long): TimeDealCouponResponse

    /** 부하테스트 전용. 락 방식과 트랜잭션 경계를 요청마다 지정한다. */
    fun issueCouponBench(
        userPrincipal: UserPrincipal,
        timeDealId: Long,
        lockMode: String,
        txWrapped: Boolean
    ): TimeDealCouponResponse
}