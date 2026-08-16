package coupong.nbc.coupongwowdeal.infra.security

import coupong.nbc.coupongwowdeal.infra.security.jwt.JwtAuthenticationFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val customAuthenticationEntrypoint: CustomAuthenticationEntrypoint,
) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .csrf { it.disable() }
            .headers { header -> header.frameOptions { it.disable() } }
            .authorizeHttpRequests {
                it.requestMatchers(
                    // 인증 대상에서 제외할 URL 설정
                    "/api/v1/auth/**",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/error",
                ).permitAll()
                    // 부하테스트용 변경: DB를 MySQL로 바꿔 H2 콘솔 자동설정이 비활성화되면서
                    // PathRequest.toH2Console() 이 H2ConsoleProperties 빈을 찾지 못해
                    // 모든 요청이 500으로 떨어졌다. 이번 측정에서 H2 콘솔은 쓰지 않으므로 제거.
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling { it.authenticationEntryPoint(customAuthenticationEntrypoint) }
            .build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        // 부하테스트용 변경: 기본 강도(10)로는 유저 3,000명 픽스처 생성에만 수십 분이 걸린다.
        // BCrypt 는 가입·로그인에서만 쓰이고 측정 대상 경로(JWT 검증 → 쿠폰 발급)에는 관여하지
        // 않으므로, 픽스처 생성 시간을 줄이기 위해 최소 강도로 낮춘다. 운영 설정이 아니다.
        return BCryptPasswordEncoder(4)
    }
}