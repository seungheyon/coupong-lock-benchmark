package coupong.nbc.coupongwowdeal.domain.timedeal.service.v1

import coupong.nbc.coupongwowdeal.domain.coupon.service.v1.CouponService
import coupong.nbc.coupongwowdeal.domain.timedeal.dto.request.CreateTimeDealRequest
import coupong.nbc.coupongwowdeal.domain.timedeal.dto.request.UpdateTimeDealRequest
import coupong.nbc.coupongwowdeal.domain.timedeal.dto.response.TimeDealCouponResponse
import coupong.nbc.coupongwowdeal.domain.timedeal.dto.response.TimeDealResponse
import coupong.nbc.coupongwowdeal.domain.timedeal.repository.v1.TimeDealRepository
import coupong.nbc.coupongwowdeal.exception.ModelNotFoundException
import coupong.nbc.coupongwowdeal.infra.security.UserPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class TimeDealServiceImpl(
    private val timeDealRepository: TimeDealRepository,
    private val couponService: CouponService
) : TimeDealService {
    @Transactional
    override fun createTimeDeal(userPrincipal: UserPrincipal, request: CreateTimeDealRequest): TimeDealResponse {
        return couponService.createCoupon(request.toCouponCreateRequest())
            .let { request.toTimeDeal(it.id) }
            .let { timeDealRepository.save(it) }
            .let { TimeDealResponse.from(it) }
    }

    override fun getTimeDeals(): List<TimeDealResponse> {
        return timeDealRepository.findAll().map { TimeDealResponse.from(it) }
    }

    @Transactional
    override fun updateTimeDeal(timeDealId: Long, timeDealUpdate: UpdateTimeDealRequest): TimeDealResponse {
        val timeDeal = timeDealRepository.findById(timeDealId) ?: throw ModelNotFoundException("timedeal", timeDealId)
        timeDeal.update(
            name = timeDealUpdate.name,
            openedAt = timeDealUpdate.openedAt,
            closedAt = timeDealUpdate.closedAt
        )
        return TimeDealResponse.from(timeDeal)
    }

    override fun deleteTimeDeal(timeDealId: Long) {
        timeDealRepository.deleteById(timeDealId)
    }

    @Transactional
    override fun issueCoupon(userPrincipal: UserPrincipal, timeDealId: Long): TimeDealCouponResponse {
        val timeDeal =
            timeDealRepository.findById(timeDealId) ?: throw ModelNotFoundException("timedeal", timeDealId)
        check(LocalDateTime.now().isBefore(timeDeal.closedAt)) { throw IllegalStateException("Time deal not opened") }
        return couponService.issueCouponToUserWithPessimisticLock(timeDeal.couponId, userPrincipal.id)
            .let { TimeDealCouponResponse.toResponse(timeDeal, it) }
    }

    /**
     * 부하테스트 전용.
     *
     * 여기에 @Transactional 을 붙이지 않는 것이 중요하다. 붙이면 바깥에서 트랜잭션이 먼저 열리고,
     * 안쪽 Transactional 블록이 기본 전파 속성(REQUIRED)에 따라 그 트랜잭션에 참여해 버린다.
     * 그러면 커밋 시점이 바깥 메서드 종료 시점으로 밀려서, 락이 반납된 뒤에 커밋되는 형태가
     * 어떤 설정을 주든 강제된다. 경계 자체를 측정 대상으로 삼으려면 여기는 비어 있어야 한다.
     */
    override fun issueCouponBench(
        userPrincipal: UserPrincipal,
        timeDealId: Long,
        lockMode: String,
        txWrapped: Boolean
    ): TimeDealCouponResponse {
        val timeDeal =
            timeDealRepository.findById(timeDealId) ?: throw ModelNotFoundException("timedeal", timeDealId)
        check(LocalDateTime.now().isBefore(timeDeal.closedAt)) { throw IllegalStateException("Time deal not opened") }
        return couponService.issueCouponBench(timeDeal.couponId, userPrincipal.id, lockMode, txWrapped)
            .let { TimeDealCouponResponse.toResponse(timeDeal, it) }
    }
}