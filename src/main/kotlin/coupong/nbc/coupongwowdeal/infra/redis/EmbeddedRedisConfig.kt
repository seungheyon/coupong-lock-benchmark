package coupong.nbc.coupongwowdeal.infra.redis

/**
 * 부하테스트용 변경(bench/lock-comparison): embedded Redis 기동을 비활성화했다.
 *
 * 원본은 @Configuration 으로 앱 부팅 시 embedded Redis 를 spring.data.redis.port 에 띄웠다.
 * 이번 측정은 Docker 로 띄운 Redis(호스트 6380)에 붙어야 하고, 측정 환경을 기록으로 남기려면
 * Redis 버전과 설정이 통제 가능해야 하므로 내장 서버를 쓰지 않는다.
 *
 * 클래스를 지우지 않고 남겨 둔 것은, 원본에 이런 구성이 있었다는 사실 자체가 측정 조건의
 * 일부이기 때문이다(당시 측정은 내장 Redis 를 대상으로 했을 가능성이 있다).
 */
class EmbeddedRedisConfig
