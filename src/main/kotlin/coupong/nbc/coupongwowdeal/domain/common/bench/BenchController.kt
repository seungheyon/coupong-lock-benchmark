package coupong.nbc.coupongwowdeal.domain.common.bench

import coupong.nbc.coupongwowdeal.domain.coupon.repository.v1.coupon.CouponJpaRepository
import coupong.nbc.coupongwowdeal.domain.timedeal.dto.response.TimeDealCouponResponse
import coupong.nbc.coupongwowdeal.domain.timedeal.service.v1.TimeDealService
import coupong.nbc.coupongwowdeal.infra.security.UserPrincipal
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 부하테스트 전용 엔드포인트(bench/lock-comparison 브랜치에서만 존재).
 *
 * 락 방식과 트랜잭션 경계를 요청 파라미터로 받아서, 앱을 재시작하지 않고 조건을 바꿔 가며 측정한다.
 */
@RestController
@RequestMapping("/api/bench")
class BenchController(
    private val timeDealService: TimeDealService,
    private val couponJpaRepository: CouponJpaRepository,
) {

    @PostMapping("/issue/{timeDealId}")
    fun issue(
        @AuthenticationPrincipal userPrincipal: UserPrincipal,
        @PathVariable timeDealId: Long,
        @RequestParam(name = "lock") lockMode: String,
        @RequestParam(name = "txWrapped", defaultValue = "false") txWrapped: Boolean,
    ): ResponseEntity<TimeDealCouponResponse> {
        return ResponseEntity.status(HttpStatus.OK)
            .body(timeDealService.issueCouponBench(userPrincipal, timeDealId, lockMode, txWrapped))
    }

    /** 회차 종료 후 남은 재고 확인용. 발급 수량 검증에 쓴다. */
    @GetMapping("/coupon/{couponId}/quantity")
    fun quantity(@PathVariable couponId: Long): ResponseEntity<Map<String, Any>> {
        val coupon = couponJpaRepository.findById(couponId).orElseThrow()
        return ResponseEntity.ok(
            mapOf(
                "couponId" to couponId,
                "remainingQuantity" to coupon.currentQuantity,
                "totalQuantity" to coupon.totalQuantity,
            )
        )
    }
}
