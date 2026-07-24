# Signal Confidence & Strategy Design Notes

## Purpose

A running collection of **conceptual observations** about how QuantStream's strategies
behave — the subtle modelling choices that aren't bugs but shape what the numbers mean.
These are the things worth knowing before you trust a chart or weight a signal, and the
kind of nuance worth being able to explain in an interview.

Unlike `docs/engineering/01-hurdles-and-fixes.md` (things that broke and how they were
fixed), this file is about **design decisions whose consequences are easy to miss**.

---

## 1. What "confidence" means on a signal

Every `Signal` carries a `confidence` in `[0, 1]`. It is **not** a probability that the
trade is profitable. It is a normalized measure of *how strongly the strategy's own
condition was met* — a strength-of-conviction score, strategy-specific by construction:

| Strategy | Confidence = 0 when… | Confidence → 1 when… |
|----------|----------------------|----------------------|
| OBI market making | `\|OBI\|` sits exactly at the threshold (e.g. 0.5) | `\|OBI\|` approaches its max of 1.0 |
| Ornstein-Uhlenbeck | z-score sits exactly at the entry band (e.g. ±2.0) | z-score stretches toward ±3σ and beyond |

Because each strategy normalizes against its *own* trigger geometry, confidences are
**comparable within a strategy but not across strategies** — an OBI 0.4 and an O-U 0.4
do not represent the same conviction. Any cross-strategy ranking (e.g. in the aggregator
or dashboard) must keep that in mind.

---

## 2. The "zero confidence at the threshold" edge (O-U, and OBI too)

### Observation

The O-U strategy fires signals with `confidence = 0.00` — e.g. BTC signalling `SELL` at
exactly `z = 2.00`, its entry threshold. This is **correct by design**: confidence is
defined as *excess beyond the threshold*, scaled toward 3σ:

```
confidence = clamp( (|z| - entryZ) / (3.0 - entryZ), 0, 1 )
```

At `|z| == entryZ` the numerator is 0, so confidence is exactly 0. OBI has the identical
shape (`(|OBI| - threshold) / (1 - threshold)`), zero right at its threshold.

### Why it's a trap

A signal firing *right at the boundary* is indistinguishable, by its confidence value,
from a signal carrying no conviction at all. That matters the moment anything
**weights by confidence**:

- A dashboard sizing markers or bars by confidence renders boundary signals as invisible
  (zero-height).
- An aggregator that weighted fill size or a consensus score by confidence would give a
  legitimately-fired boundary signal **zero weight** — effectively dropping it, even
  though the strategy did decide to trade.

The signal *did* fire — crossing the threshold is the trade decision. Confidence is a
*secondary* magnitude, and conflating "fired with minimum conviction" with "didn't fire"
is the subtle error.

### Options if this becomes a problem

1. **Floor confidence** — give an entry a small non-zero base (e.g. `0.1 + 0.9 × excess`)
   so a boundary fire is visibly a real signal, not a null. Simple; slightly distorts the
   low end.
2. **Two fields** — keep a boolean/enum "fired" separate from a continuous "strength", so
   consumers never infer one from the other. Cleaner separation of concerns; more schema.
3. **Consumers never treat confidence as presence** — document that a signal's *existence*
   is the decision and confidence is only a tiebreaker. Zero code change; relies on every
   consumer respecting the contract.

**Current stance:** left as-is (option 3, documented here). No consumer yet weights by
confidence in a way that drops boundary signals. Revisit when the dashboard or aggregator
starts using confidence for sizing/ranking — at which point option 1 is the likely pick.

---

## 3. Warmup is not backfill — and its output starts empty *by design*

Stateful strategies (O-U, and future VPIN/pairs/vol) emit **nothing** until their rolling
window is full — O-U's is 600 observations (~10 min at 1 obs/sec). During that window the
strategy is silent. This is the intraday replacement for database backfill (see
`docs/planning/03-historical-data-and-retention.md`): the lookback is built *forward* from
the live stream, never loaded from disk.

**Consequence to remember:** a freshly (re)started strategy engine produces OBI signals
immediately but O-U signals only after ~10 minutes. A gap in a strategy's output right
after startup is expected, not a fault. The dashboard should show a "warming up" state
rather than an empty chart that reads like a bug.

---

## 4. Realized PnL and win-rate are event-driven — zeros can be correct

(Cross-reference: `docs/engineering/01-hurdles-and-fixes.md` #8.)

Realized PnL and win-rate only change when a fill **reduces, closes, or flips** a
position — never on one that opens or accumulates. A young paper portfolio therefore
legitimately shows `realized_pnl = 0` and `win_rate = 0` until its first position is
reduced. And win-rate at a handful of closed trades is **not statistically meaningful** —
it needs dozens of closes before the number says anything. Treat early win-rate as noise,
and surface a sample-size caveat on the dashboard rather than presenting `0.667` (2 of 3)
as if it were a real hit rate.

---

## 5. Notional sizing, not unit lots — so PnL is comparable across tokens

(Cross-reference: `docs/engineering/01-hurdles-and-fixes.md` #7,
`docs/planning/05-signal-aggregator.md`.)

The aggregator sizes each simulated fill by a fixed **notional** (`units = notional /
price`), not a fixed unit count. A flat 100-unit lot would put ~$5.8M of risk on a $58k
BTC but only ~$20k on a $196 JPM, so a strategy's PnL would be dominated by *which*
high-priced tokens it touched rather than by whether its signals were good. Notional
sizing gives every token comparable dollar risk, which is what makes the per-strategy PnL
rollup a meaningful cross-strategy comparison. The lesson generalizes: **when you
aggregate a metric across assets that differ by orders of magnitude in price, normalize
the unit of risk first.**
