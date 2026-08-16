package coupong.nbc.coupongwowdeal.infra.redis

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.redisson.config.Config as RedissonConfig

/**
 * 부하테스트용 변경(bench/lock-comparison):
 *  - embedded Redis 기동(@PostConstruct startRedis) 제거. 외부 Redis(Docker)에 접속한다.
 *  - LettuceConnectionFactory 가 인자 없이 생성돼 localhost:6379 고정이던 것을
 *    설정값(spring.data.redis.host/port)을 따르도록 변경.
 */
@Configuration
@EnableRedisRepositories
class RedisConfig(
    @Value("\${spring.data.redis.host}") private val host: String,
    @Value("\${spring.data.redis.port}") private val port: Int,
) {

    @Bean
    fun redisConnectionFactory(): RedisConnectionFactory {
        return LettuceConnectionFactory(RedisStandaloneConfiguration(host, port))
    }

    @Bean
    fun redisTemplate(redisConnectionFactory: RedisConnectionFactory): RedisTemplate<String, String> {
        val redisTemplate = RedisTemplate<String, String>()
        redisTemplate.connectionFactory = redisConnectionFactory
        redisTemplate.keySerializer = StringRedisSerializer()
        redisTemplate.valueSerializer = StringRedisSerializer()
        return redisTemplate
    }

    @Bean(destroyMethod = "shutdown")
    fun redissonClient(): RedissonClient {
        val config = RedissonConfig()
        config.useSingleServer().address = "redis://$host:$port"
        return Redisson.create(config)
    }
}
