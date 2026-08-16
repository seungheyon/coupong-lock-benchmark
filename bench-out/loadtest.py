"""
Coupong 동시성 제어 3안 부하테스트 드라이버.

측정 대상
  lock  : pessimistic | spin | redisson
  tx    : inner(락이 트랜잭션을 감쌈 = 정상) | wrapped(트랜잭션이 락을 감쌈 = 버그 형태)
  n     : 동시 요청 수 (500 / 1000 / 3000)

산출물 (bench-out/results/)
  raw_<lock>_<tx>_<n>.csv   요청별 원시 기록 (다른 툴로 다시 그릴 수 있게 남긴다)
  summary.csv               회차별 집계
  manifest.json             측정 조건 메타데이터

주의: 이 수치는 2024년 당시 측정치(4,400ms → 1,800ms)를 검증하지 않는다.
하드웨어·JVM·DB(당시 H2 추정 / 지금 MySQL 8.0.46)·요청 수가 모두 다르다.
"""
import csv
import json
import os
import platform
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

BASE = "http://127.0.0.1:8080"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")
TOKENS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "jwt_tokens.txt")

LOCKS = ["pessimistic", "spin", "redisson"]
TXS = ["inner", "wrapped"]
NS = [500, 1000, 3000]
COUPON_QUANTITY = 1000
TIME_DEAL_ID = 1
COUPON_ID = 1


def sql(statement):
    """컨테이너 안에서 직접 SQL 실행. 회차 사이 상태 초기화에 쓴다."""
    return subprocess.run(
        ["docker", "exec", "coupong-mysql", "mysql", "-uroot", "-pbenchroot", "coupong", "-N", "-B", "-e", statement],
        capture_output=True, text=True,
    ).stdout.strip()


def reset_state():
    """발급 이력을 지우고 재고를 원복한다. 유저 픽스처는 그대로 둔다."""
    sql("DELETE FROM coupon_user;")
    sql(f"UPDATE coupon SET current_quantity = {COUPON_QUANTITY} WHERE id = {COUPON_ID};")
    subprocess.run(["docker", "exec", "coupong-redis", "redis-cli", "FLUSHALL"], capture_output=True)


def remaining_quantity():
    v = sql(f"SELECT current_quantity FROM coupon WHERE id = {COUPON_ID};")
    return int(v) if v else None


def issued_count():
    v = sql("SELECT COUNT(*) FROM coupon_user;")
    return int(v) if v else None


def one_request(token, lock, tx_wrapped):
    url = f"{BASE}/api/bench/issue/{TIME_DEAL_ID}?lock={lock}&txWrapped={'true' if tx_wrapped else 'false'}"
    req = urllib.request.Request(url, method="POST")
    req.add_header("Authorization", f"Bearer {token}")
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            resp.read()
            status = resp.status
            err = ""
    except urllib.error.HTTPError as e:
        status = e.code
        err = (e.read()[:200].decode("utf-8", "replace") or "").replace("\n", " ")
    except Exception as e:  # 연결 실패/타임아웃
        status = -1
        err = f"{type(e).__name__}: {e}"[:200]
    elapsed_ms = (time.perf_counter() - t0) * 1000
    return status, elapsed_ms, err


def percentile(sorted_vals, p):
    if not sorted_vals:
        return None
    k = (len(sorted_vals) - 1) * p / 100.0
    lo, hi = int(k), min(int(k) + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def run_case(tokens, lock, tx, n, warmup):
    tx_wrapped = tx == "wrapped"
    reset_state()
    time.sleep(1)

    rows = []
    started_at = datetime.now(timezone.utc).isoformat()
    wall0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=n) as pool:
        futures = [pool.submit(one_request, tokens[i], lock, tx_wrapped) for i in range(n)]
        for i, f in enumerate(futures):
            status, ms, err = f.result()
            rows.append({"idx": i, "status": status, "elapsed_ms": round(ms, 3), "error": err})
    wall_ms = (time.perf_counter() - wall0) * 1000

    time.sleep(1)
    remaining = remaining_quantity()
    issued = issued_count()

    ok = [r["elapsed_ms"] for r in rows if r["status"] == 200]
    ok_sorted = sorted(ok)
    expected_issued = min(n, COUPON_QUANTITY)
    # 재고 오차: 실제 발급된 행 수와 재고 차감량이 어긋난 정도
    stock_consumed = COUPON_QUANTITY - remaining if remaining is not None else None
    overissue = (issued - expected_issued) if issued is not None else None
    mismatch = (issued - stock_consumed) if (issued is not None and stock_consumed is not None) else None

    os.makedirs(OUT, exist_ok=True)
    tag = f"{lock}_{tx}_{n}" + ("_warmup" if warmup else "")
    with open(os.path.join(OUT, f"raw_{tag}.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=["idx", "status", "elapsed_ms", "error"])
        w.writeheader()
        w.writerows(rows)

    return {
        "lock": lock, "tx": tx, "requests": n, "warmup": warmup,
        "started_at": started_at,
        "wall_ms": round(wall_ms, 1),
        "success": len(ok),
        "http_error": sum(1 for r in rows if r["status"] not in (200, -1)),
        "conn_error": sum(1 for r in rows if r["status"] == -1),
        "avg_ms": round(sum(ok) / len(ok), 1) if ok else None,
        "p50_ms": round(percentile(ok_sorted, 50), 1) if ok else None,
        "p95_ms": round(percentile(ok_sorted, 95), 1) if ok else None,
        "p99_ms": round(percentile(ok_sorted, 99), 1) if ok else None,
        "max_ms": round(max(ok), 1) if ok else None,
        "issued_rows": issued,
        "remaining_quantity": remaining,
        "stock_consumed": stock_consumed,
        "expected_issued": expected_issued,
        "overissue": overissue,
        "issued_vs_stock_mismatch": mismatch,
    }


def main():
    with open(TOKENS_FILE, encoding="utf-8") as fh:
        tokens = [t.strip() for t in fh if t.strip()]
    print(f"tokens loaded: {len(tokens)}")
    if len(tokens) < max(NS):
        sys.exit(f"토큰이 부족하다: {len(tokens)} < {max(NS)}")

    summaries = []
    # 예열 1회. JIT·커넥션 풀·Hibernate 캐시가 채워지지 않은 상태의 첫 회차를
    # 본 측정에 섞지 않기 위한 것이고, 집계에서 제외한다.
    print("[warmup] pessimistic/inner/500")
    run_case(tokens, "pessimistic", "inner", 500, warmup=True)

    for n in NS:
        for lock in LOCKS:
            for tx in TXS:
                print(f"[run] lock={lock} tx={tx} n={n} ...", flush=True)
                s = run_case(tokens, lock, tx, n, warmup=False)
                summaries.append(s)
                print(f"      avg={s['avg_ms']}ms p95={s['p95_ms']}ms "
                      f"success={s['success']} issued={s['issued_rows']} "
                      f"remaining={s['remaining_quantity']} overissue={s['overissue']}", flush=True)

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "summary.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=list(summaries[0].keys()))
        w.writeheader()
        w.writerows(summaries)

    manifest = {
        "measured_at": datetime.now(timezone.utc).isoformat(),
        "purpose": "Coupong 동시성 제어 3안 재측정. 2024년 측정치의 검증이 아니라 현재 조건의 독립 측정.",
        "app": {
            "repo_branch": "bench/lock-comparison",
            "base_commit": "acc24c6 (dev-mysql-lock-pessimistic)",
            "spring_boot": "3.3.1", "kotlin": "1.9.24", "jvm_build": "JDK 17 (gradle toolchain)",
        },
        "infra": {
            "db": "MySQL 8.0.46 (docker, host port 3307)",
            "redis": "Redis 7.4.10 (docker, host port 6380)",
            "note": "원본 프로젝트는 H2 인메모리 + embedded Redis 구성이었다. 이번 측정은 실제 MySQL/Redis 이므로 조건이 다르다.",
        },
        "fixture": {"users": 3000, "coupon_total_quantity": COUPON_QUANTITY,
                    "source": "저장소 DataInitializer(#49, 2024-07-10)에 남아 있던 값"},
        "load": {"request_counts": NS, "concurrency": "요청 수와 동일(ThreadPoolExecutor max_workers=n)",
                 "ramp_up": "없음(전량 동시 제출)", "warmup": "pessimistic/inner/500 1회, 집계 제외"},
        "matrix": {"lock": LOCKS, "tx": TXS},
        "host": {"platform": platform.platform(), "python": platform.python_version()},
        "caveats": [
            "예열 조건을 통제했으나 2024년 측정에는 예열 기록이 없어 비교 불가.",
            "단일 인스턴스 측정이다. JVM 락이 무효가 되는 다중 인스턴스 조건은 재현하지 않았다.",
            "spin 은 15~30ms 랜덤 백오프를 도는 원본 구현 그대로다.",
        ],
    }
    with open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)

    print(f"\n완료. 결과: {OUT}")


if __name__ == "__main__":
    main()
