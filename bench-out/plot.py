"""
summary.csv 만 읽어서 그래프를 그린다.
(원시 데이터가 CSV 로 남아 있으므로 다른 툴로도 같은 그림을 다시 그릴 수 있다.)
"""
import csv
import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "results")
FIG = os.path.join(HERE, "figures")
os.makedirs(FIG, exist_ok=True)

matplotlib.rcParams["font.family"] = ["Malgun Gothic", "DejaVu Sans"]
matplotlib.rcParams["axes.unicode_minus"] = False

LOCK_LABEL = {"pessimistic": "DB 비관적 락", "spin": "Lettuce 스핀락", "redisson": "Redisson"}
COLOR = {"pessimistic": "#2f6f9f", "spin": "#c1553b", "redisson": "#4c9a5a"}
NS = [500, 1000, 3000]

rows = list(csv.DictReader(open(os.path.join(RES, "summary.csv"), encoding="utf-8")))


def pick(lock, tx, field):
    out = []
    for n in NS:
        m = [r for r in rows if r["lock"] == lock and r["tx"] == tx and int(r["requests"]) == n]
        out.append(float(m[0][field]) if m and m[0][field] else None)
    return out


def line_chart(tx, field, title, ylabel, fname):
    fig, ax = plt.subplots(figsize=(7.5, 4.6))
    x = range(len(NS))
    for lock in ["pessimistic", "redisson", "spin"]:
        ax.plot(x, pick(lock, tx, field), marker="o", label=LOCK_LABEL[lock], color=COLOR[lock], linewidth=2)
    ax.set_xticks(list(x)); ax.set_xticklabels([f"{n:,}건" for n in NS])
    ax.set_xlabel("동시 요청 수"); ax.set_ylabel(ylabel)
    ax.set_title(title, fontsize=12)
    ax.grid(alpha=.3, linestyle=":"); ax.legend()
    ax.spines[["top", "right"]].set_visible(False)
    fig.tight_layout(); fig.savefig(os.path.join(FIG, fname), dpi=150); plt.close(fig)


# 1) 정상 경계(inner) 기준 평균/ p95
line_chart("inner", "avg_ms", "락 방식별 평균 응답시간 (락이 트랜잭션을 감싸는 정상 구성)",
           "평균 응답시간 (ms)", "avg_inner.png")
line_chart("inner", "p95_ms", "락 방식별 p95 응답시간 (정상 구성)",
           "p95 응답시간 (ms)", "p95_inner.png")

# 2) 정합성: 트랜잭션 경계가 어긋났을 때 재고 불일치
fig, ax = plt.subplots(figsize=(7.5, 4.6))
w = 0.26
for i, lock in enumerate(["pessimistic", "spin", "redisson"]):
    vals = [abs(float(v or 0)) for v in pick(lock, "wrapped", "issued_vs_stock_mismatch")]
    ax.bar([j + (i - 1) * w for j in range(len(NS))], vals, width=w,
           label=LOCK_LABEL[lock], color=COLOR[lock])
ax.set_xticks(range(len(NS))); ax.set_xticklabels([f"{n:,}건" for n in NS])
ax.set_xlabel("동시 요청 수"); ax.set_ylabel("발급 건수와 재고 차감량의 불일치")
ax.set_title("트랜잭션이 락을 감쌌을 때(버그 구성) 발생한 재고 불일치", fontsize=12)
ax.grid(alpha=.3, linestyle=":", axis="y"); ax.legend()
ax.spines[["top", "right"]].set_visible(False)
fig.tight_layout(); fig.savefig(os.path.join(FIG, "mismatch_wrapped.png"), dpi=150); plt.close(fig)

# 3) 스핀락 -> Redisson 교체 효과 (정상 구성 기준)
fig, ax = plt.subplots(figsize=(7.5, 4.6))
spin = pick("spin", "inner", "avg_ms")
rdsn = pick("redisson", "inner", "avg_ms")
x = range(len(NS))
ax.bar([i - 0.19 for i in x], spin, width=0.38, label="Lettuce 스핀락", color=COLOR["spin"])
ax.bar([i + 0.19 for i in x], rdsn, width=0.38, label="Redisson", color=COLOR["redisson"])
for i in x:
    imp = (spin[i] - rdsn[i]) / spin[i] * 100
    ax.text(i, max(spin[i], rdsn[i]) * 1.03, f"{imp:.1f}% 감소", ha="center", fontsize=10)
ax.set_xticks(list(x)); ax.set_xticklabels([f"{n:,}건" for n in NS])
ax.set_xlabel("동시 요청 수"); ax.set_ylabel("평균 응답시간 (ms)")
ax.set_title("Lettuce 스핀락 → Redisson pub/sub 락 교체 효과 (정상 구성)", fontsize=12)
ax.grid(alpha=.3, linestyle=":", axis="y"); ax.legend()
ax.spines[["top", "right"]].set_visible(False)
fig.tight_layout(); fig.savefig(os.path.join(FIG, "spin_vs_redisson.png"), dpi=150); plt.close(fig)

print("figures ->", FIG)
for f in sorted(os.listdir(FIG)):
    print("  ", f)
