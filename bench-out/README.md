> 이 저장소의 애플리케이션 코드는 팀 프로젝트([coupong-wowdeal/coupong-wowdeal](https://github.com/coupong-wowdeal/coupong-wowdeal))에서 온 것이고,
> 개인 작업은 `bench-out/` 과 `BoundaryLeakTest` 다. 전체 요약은 [루트 README](../README.md) 참고.

# Coupong 락 3안 부하테스트 재측정

2024-07 Coupong 프로젝트의 동시성 제어 3안을 현재 조건에서 다시 측정한 기록.

## 이 측정이 아닌 것

**2024년 당시 수치(4,400ms → 1,800ms, 59% 개선)의 검증이 아니다.** 하드웨어·JVM·DB·요청 수가
모두 다르다. 특히 원본 프로젝트는 H2 인메모리 + embedded Redis 구성이었고(모든 브랜치의
build.gradle.kts 에 MySQL 드라이버가 없다), 이번 측정은 Docker MySQL 8.0.46 + Redis 7.4.10 이다.
당시 측정 조건(요청 수, 예열 여부)은 저장소에 남아 있지 않다.

## 측정 조건

| 항목 | 값 |
|---|---|
| 기준 커밋 | `acc24c6` (dev-mysql-lock-pessimistic) |
| DB | MySQL 8.0.46 (Docker, host 3307) |
| Redis | Redis 7.4.10 (Docker, host 6380) |
| Spring Boot / Kotlin | 3.3.1 / 1.9.24 |
| 픽스처 | 유저 3,000명, 쿠폰 총 수량 1,000개 (저장소 DataInitializer #49 의 값) |
| 요청 수 | 500 / 1,000 / 3,000 (전량 동시 제출, 램프업 없음) |
| 예열 | pessimistic/inner/500 1회, 집계 제외 |

## 측정 축

- **lock**: `pessimistic`(DB 비관적 락) / `spin`(Lettuce setnx 스핀락, 15~30ms 랜덤 백오프) / `redisson`(pub-sub 락)
- **tx**: `inner`(락이 트랜잭션을 감쌈 = 정상) / `wrapped`(트랜잭션이 락을 감쌈 = 커밋 전 언락 버그)

## 산출물

```
results/raw_<lock>_<tx>_<n>.csv   요청별 원시 기록 (status, elapsed_ms, error)
results/summary.csv               회차별 집계
results/manifest.json             측정 조건과 한계
figures/*.png                     그래프 (summary.csv 만으로 재생성 가능)
loadtest.py / plot.py             드라이버와 작도 스크립트
```

`summary.csv` 와 `raw_*.csv` 만 있으면 다른 도구로 같은 그래프를 다시 그릴 수 있다.

## 재현

```bash
docker run -d --name coupong-mysql -p 3307:3306 -e MYSQL_ROOT_PASSWORD=benchroot -e MYSQL_DATABASE=coupong mysql:8.0
docker run -d --name coupong-redis -p 6380:6379 redis:7-alpine
./gradlew bootRun            # DataInitializer 가 픽스처와 jwt_tokens.txt 생성
python bench-out/loadtest.py
python bench-out/plot.py
```

`src/main/resources/application.yml` 은 gitignore 대상이라 저장소에 없다. JWT 시크릿이 들어가기
때문이며, 재현 시 직접 만들어야 한다.
