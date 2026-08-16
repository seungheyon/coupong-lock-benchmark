package coupong.nbc.coupongwowdeal.domain.common.initializer

import coupong.nbc.coupongwowdeal.domain.timedeal.dto.request.CreateTimeDealRequest
import coupong.nbc.coupongwowdeal.domain.timedeal.service.v1.TimeDealService
import coupong.nbc.coupongwowdeal.domain.user.dto.v1.request.SignUpRequest
import coupong.nbc.coupongwowdeal.domain.user.model.v1.UserRole
import coupong.nbc.coupongwowdeal.domain.user.service.v1.UserService
import coupong.nbc.coupongwowdeal.infra.security.jwt.JwtPlugin
import coupong.nbc.coupongwowdeal.infra.security.UserPrincipal
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.io.File
import java.time.LocalDateTime

/**
 * 부하테스트 픽스처 생성.
 *
 * 유저 수와 쿠폰 수량은 원본 저장소의 DataInitializer(#49, 2024-07-10)에 남아 있던 값
 * (userSize = 3000, couponQuantity = 1000)을 그대로 쓴다. 저장소에 남은 유일한 근거값이다.
 *
 * 원본은 코루틴으로 병렬 가입시켰으나, 여기서는 순차로 만든다. 픽스처 생성 속도는 측정 대상이
 * 아니고, 병렬 가입은 그 자체가 경합을 만들어 초기 상태를 흔들 수 있기 때문이다.
 */
@Configuration
class DataInitializer(
    private val userService: UserService,
    private val jwtPlugin: JwtPlugin,
    private val timeDealService: TimeDealService,
    @Value("\${bench.user-size}") private val userSize: Int,
    @Value("\${bench.coupon-quantity}") private val couponQuantity: Int,
    @Value("\${bench.token-file}") private val tokenFile: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    @Profile("!test")
    fun initData() = ApplicationRunner {
        val started = System.currentTimeMillis()

        // 타임딜 생성에는 ADMIN 권한이 필요하다. User.role 이 val 이라 가입 후 승격이 불가능하므로,
        // 저장소에 이미 있는 tokenTestGenerate()(id=1 이 없으면 ADMIN 으로 생성)를 그대로 쓴다.
        userService.tokenTestGenerate()
        val adminId = 1L

        repeat(userSize) { i ->
            userService.signUp(SignUpRequest("user${i + 1}", "password", "password"))
        }
        log.info("[bench] users created: {} (+admin id={})", userSize, adminId)

        // signIn() 을 3,000번 호출하면 BCrypt 검증이 그만큼 반복된다. 토큰 발급 자체는
        // JwtPlugin 이 하는 일이므로 직접 만든다. admin 이 id=1 이고 그 뒤로 user1..userN 이
        // 순서대로 저장되므로 id 는 2..N+1 이다.
        val tokens = (1..userSize).map { i ->
            jwtPlugin.generateAccessToken((i + 1).toString(), UserRole.USER.name)
        }
        File(tokenFile).apply { parentFile?.mkdirs() }.writeText(tokens.joinToString("\n"))
        log.info("[bench] jwt tokens written: {} -> {}", tokens.size, tokenFile)

        val timeDeal = timeDealService.createTimeDeal(
            UserPrincipal(adminId, setOf("ROLE_ADMIN")),
            CreateTimeDealRequest(
                name = "benchTimeDeal",
                openedAt = LocalDateTime.now(),
                closedAt = LocalDateTime.now().plusDays(7),
                couponName = "benchCoupon",
                couponExpiredAt = LocalDateTime.now().plusDays(7),
                couponDiscountPrice = 1000,
                couponTotalQuantity = couponQuantity
            )
        )
        log.info(
            "[bench] fixture ready in {}ms | timeDealId={} couponQuantity={}",
            System.currentTimeMillis() - started, timeDeal.id, couponQuantity
        )
    }
}
