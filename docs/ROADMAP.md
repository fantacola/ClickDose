# ClickPlan — Roadmap

Realistic milestones. No speculative timelines.

---

## v1.0 — Foundation ✓

- [x] Onboarding: pen setup + calibration prompt
- [x] Home screen with this week's target clicks as dominant element
- [x] Fullscreen click counting with microphone
- [x] Vibration feedback at target click count
- [x] Save dose — subtracts from remaining pen clicks
- [x] Remaining clicks and doses tracking
- [x] Injection history log (local, offline)
- [x] Manual +1 / −1 correction during counting
- [x] German and English interface
- [x] Pen calibration: noise baseline + click peak detection
- [x] Full-dose measurement to verify pen total

## v1.1 — Pen lifecycle ✓

- [x] Pen end reconciliation: expected vs actual clicks
- [x] "Pen reached stop" flow — silently adjusts state, no error message
- [x] New pen flow: reset clicks, optional re-measure
- [x] Pen number tracking for history

## v1.2 — Reduction assistant ✓

- [x] At new pen only: keep dose or reduce one step
- [x] No mid-pen dose changes enforced by UI
- [x] 0.85× reduction with zero-remainder optimisation (±5 clicks)
- [x] PlanMode enum: KEEP_CURRENT / REDUCE_TO_TARGET (internal state)
- [x] Plan settings placeholder in More menu

## v1.3 — Architecture & UX quality (current)

- [x] Two-step onboarding with proper Back navigation
- [x] No dead ends in any flow
- [x] Calibration field labels clarified (dose strength per injection)
- [x] Plan mode persisted across sessions
- [x] Dead code removed (old planner dialog)
- [x] Project documentation (README, VISION, PRINCIPLES, ROADMAP)

## v2.0 — Smart mode (future)

- [ ] Adaptive planner: automatically suggest reduction pace
- [ ] Pause reduction option
- [ ] Set explicit target dose
- [ ] User opts in explicitly — defaults remain simple

## Not planned

- Cloud sync or user accounts
- Weight / BMI / calorie tracking
- Food noise tracking
- Gamification or badges
- Community features
- Medical recommendations
- iOS version (not planned)
