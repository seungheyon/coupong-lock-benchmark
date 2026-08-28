# coupong-lock-benchmark

선착순 쿠폰 발급에서 **동시성 제어 3안**(DB 비관적 락 / Lettuce 스핀락 / Redisson pub-sub 락)과
**트랜잭션 경계 2종**을 교차해 측정한 기록.

측정의 출발점은 하나의 질문이었다. 2024년 팀 프로젝트에서 "락 해제가 커밋보다 먼저 일어나는" 버그를
발견했을 때 관측된 오차는 1~3%였는데, 나중에 다시 재보니 요청의 절반 가까이가 어긋났다.
**같은 버그인데 왜 규모가 두 자릿수 배로 다른가.**

---

## 출처와 기여 범위

애플리케이션 코드는 **팀 프로젝트에서 가져온 것**이다. 이 저장소는 fork로 만들어지지 않아 GitHub에
fork 표시가 뜨지 않으므로 여기에 명시한다.

- 원본: **[coupong-wowdeal/coupong-wowdeal](https://github.com/coupong-wowdeal/coupong-wowdeal)**
  (내일배움캠프 실전 프로젝트, 2024.07.02 ~ 07.11 · Kotlin / Spring Boot · 기여자 3인)
- 이 저장소의 커밋 25건 중 **팀 커밋이 23건, 개인 작업은 마지막 2건**이다.
  기준 커밋 `acc24c6` 까지가 팀의 작업물이며, 원본 저장소에 동일한 SHA로 존재한다.
- 개인 작업: `9b1607f`(락 3안 × 경계 2종 × 요청수 3종 부하테스트), `6af8b19`(경계 누수 규모를
  결정하는 변수 분리 실험). 추가된 것은 `bench-out/` 전체와 `BoundaryLeakTest`다.

원본 저장소에는 별도 라이선스가 명시돼 있지 않다. 재사용을 고려한다면 원본을 먼저 확인할 것.

---

## 실험 A — 락 3안 × 트랜잭션 경계

`inner`는 락이 트랜잭션을 감싸는 정상 구성, `wrapped`는 트랜잭션이 락을 감싸 **커밋 전에 락이 풀리는**
버그 구성이다. 재고 1,000개 고정, 요청 500 / 1,000 / 3,000건을 램프업 없이 전량 동시 제출했다.

### 정합성 — 경계가 전부를 가른다

| 요청 수 | `inner` (정상 경계) | `wrapped` (커밋 전 언락) |
|---|---|---|
| 500 | 재고 불일치 **0** (3안 모두) | 불일치 **248~250건** |
| 1,000 | 재고 불일치 **0** (3안 모두) | 불일치 **494~497건** |
| 3,000 | 발급 1,000건, **초과발급 0** | 발급 **2,000건 — 재고 1,000개에 2배 발급** |

락 방식을 무엇으로 고르든 경계가 틀리면 무너지고, 경계가 맞으면 셋 다 정합성을 지킨다.
**락 선택보다 경계가 상위 변수였다.**

### 성능 (정상 경계 기준, 평균 응답시간)

| 요청 수 | 비관적 락 | 스핀락 | Redisson |
|---|---:|---:|---:|
| 500 | **4,102 ms** | 6,772 ms | 4,418 ms |
| 1,000 | **5,706 ms** | 13,982 ms | 8,860 ms |
| 3,000 | **10,578 ms** | 16,937 ms | 13,841 ms |

이 조건에서는 비관적 락이 가장 빨랐고 스핀락이 일관되게 가장 느렸다(백오프 15~30ms 랜덤 재시도).
`wrapped` 회차가 더 빠르게 찍히는 경우가 있으나 **락을 일찍 풀어 얻은 수치라 비교 대상이 아니다.**

---

## 실험 B — 왜 같은 버그의 규모가 다른가

새는 구간은 `락 해제 → 커밋` 하나뿐이다. 그렇다면 **창의 크기 = 커밋에 걸리는 시간**이라는 가설이
선다. 재고 500 / 유저 1,000으로 고정하고 DB와 동시성 모델만 바꿔 확인했다.

| DB | 동시성 | 불일치 비율 |
|---|---|---:|
| H2 인메모리 | 코루틴 `Dispatchers.IO` | 3.60% |
| H2 인메모리 | 전용 스레드풀 1,000 | 1.20% |
| MySQL 8.0 (fsync 2회/커밋) | 코루틴 | **99.40%** |
| MySQL 8.0 (fsync 2회/커밋) | 스레드풀 1,000 | **99.80%** |
| MySQL 8.0 (fsync 끔) | 코루틴 | 46.40% |
| MySQL 8.0 (fsync 끔) | 스레드풀 1,000 | 56.60% |

- **동시성 모델은 거의 무관하다.** DB를 고정하고 코루틴 ↔ 스레드 1,000개를 바꿔도
  H2는 3.60%↔1.20%, MySQL은 99.40%↔99.80%로 움직이지 않는다.
- **DB가 결정한다.** 동시성을 고정하고 DB만 바꾸면 3.60% → 99.40%, 약 27배다.
- **그중 절반 이상이 fsync다.** `innodb_flush_log_at_trx_commit=0` + `sync_binlog=0`으로 끄면
  99.40% → 46.40%로 떨어진다.

결론은 이렇다. **오차가 작게 보이는 것이 버그가 작다는 뜻이 아니다.** 인메모리 DB는 커밋이 즉시라
창이 거의 없고, 그래서 같은 결함이 1~3%로만 보인다. 2024년에 본 1~3%와 나중에 본 50%는
서로 다른 버그가 아니라, 같은 버그를 다른 환경에서 본 것이었다.

---

## 이 측정이 아닌 것

**2024년 당시 수치(4,400ms → 1,800ms, 59% 개선)의 검증이 아니다.** 하드웨어·JVM·DB·요청 수가
모두 다르다. 원본 프로젝트는 H2 인메모리 + embedded Redis 구성이었고(모든 브랜치의
`build.gradle.kts`에 MySQL 드라이버가 없다), 이번 측정은 Docker MySQL 8.0.46 + Redis 7.4.10이다.
당시 측정 조건(요청 수, 예열 여부)은 저장소에 남아 있지 않아 재현할 수 없다.

### 그 밖의 한계

- 실험 B에서 fsync를 뺀 뒤에도 남는 46%의 내역(네트워크 왕복 / 엔진 처리 / 그 밖)을 분해하지 않았다.
- MySQL은 Docker 컨테이너이며, 호스트와의 경계 비용이 포함된 값이다.
- 실험 B는 재고 500 / 유저 1,000 한 조합에서만 측정했다.
- 요청 1,000건 회차에서 커넥션 오류가 5~25건 발생했다(집계에 그대로 반영).

---

## 측정 조건

| 항목 | 값 |
|---|---|
| 기준 커밋 | `acc24c6` (dev-mysql-lock-pessimistic) |
| DB / Redis | MySQL 8.0.46 (Docker, 3307) / Redis 7.4.10 (Docker, 6380) |
| Spring Boot / Kotlin / JVM | 3.3.1 / 1.9.24 / JDK 17 |
| 픽스처 | 유저 3,000명, 쿠폰 총 수량 1,000개 (저장소 `DataInitializer` #49의 값) |
| 부하 | 500 / 1,000 / 3,000건, 램프업 없이 전량 동시 제출 |
| 예열 | pessimistic/inner/500 1회, 집계 제외 |

---

## 산출물

```
bench-out/README.md              실험 A 개요와 재현 절차
bench-out/results/summary.csv    회차별 집계 (18행)
bench-out/results/raw_*.csv      요청별 원시 기록 19개 (status, elapsed_ms, error)
bench-out/results/manifest.json  측정 조건과 한계 (기계 판독용)
bench-out/figures/*.png          그래프 4종
bench-out/boundary/RESULT.md     실험 B 결과와 해석
bench-out/boundary/r-*.txt       실험 B 각 실행의 콘솔 출력 원문
bench-out/loadtest.py            부하 드라이버
bench-out/plot.py                작도 스크립트
```

`summary.csv`와 `raw_*.csv`만 있으면 다른 도구로 같은 그래프를 다시 그릴 수 있다.

## 재현

```bash
docker run -d --name coupong-mysql -p 3307:3306 \
  -e MYSQL_ROOT_PASSWORD=benchroot -e MYSQL_DATABASE=coupong mysql:8.0
docker run -d --name coupong-redis -p 6380:6379 redis:7-alpine

./gradlew bootRun            # DataInitializer 가 픽스처와 jwt_tokens.txt 생성
python bench-out/loadtest.py
python bench-out/plot.py
```

`src/main/resources/application.yml`은 JWT 시크릿이 들어가므로 gitignore 대상이며 저장소에 없다.
재현하려면 직접 만들어야 한다. 실험 B용 예시는
[`bench-out/boundary/application-test-h2.yml.example`](bench-out/boundary/application-test-h2.yml.example)에
시크릿을 마스킹해 두었다.
