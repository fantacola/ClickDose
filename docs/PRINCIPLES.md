# ClickPlan — Design Principles

These principles guide every product and technical decision.
When in doubt, refer back to this list.

---

## 1. Simplicity over features

Every feature added is a decision the user must make.
Every decision is a source of friction.
When choosing between A (more features) and B (cleaner), always choose B.

## 2. The user should never calculate manually

The app always provides the exact number of clicks for the current week.
No division. No remainder. No mental math.

## 3. The user should never plan manually

Planning is automatic based on pen strength, doses per pen, and remaining clicks.
The only planning decision exposed to the user: "Keep current dose or reduce one step."
That decision happens once — at new pen start.

## 4. Dose changes happen only when starting a new pen

Changing dose mid-pen creates a remainder that cannot be cleanly reconciled.
The app prevents mid-pen dose changes by design.
A pen is a psychological unit. Stability within a pen reduces anxiety.

## 5. The pen endpoint is the truth

If ClickPlan expected 74 clicks remaining but the physical pen stops at 69:
- The pen is considered empty
- The remaining clicks are set to zero
- The internal state is corrected silently

This is not an error. Pens vary slightly. The app adapts.

## 6. Clicks are authoritative — mg values are estimates

All mg values shown in the app are calculated from click counts, calibration data,
and pen specifications provided by the user.

They are shown as guidance. They are never authoritative medical values.
The user's doctor sets the dose. ClickPlan counts the clicks.

## 7. Every user decision must be reversible

No irreversible destructive actions without confirmation.
Every flow must have a Back option.
No dead ends.

## 8. Minimize user input

Only ask for information the app cannot derive automatically.
The minimum required input is: pen name, dose strength (mg), doses per pen, clicks per full dose.
Nothing more.

## 9. No feature overload

Not planned and actively avoided:
- Weight / BMI / calorie tracking
- Food noise tracking
- Gamification or badges
- Community or social features
- Complex statistics
- Manual planning tables

## 10. Offline-first

All data stays on the device.
No account required.
No cloud dependency.
The app works without internet access.

## 11. Internationalisation-ready

All visible text is managed through a single `tr(key)` function.
German and English are maintained in parallel.
Adding a new language requires only a new `case` block — no external files, no build tooling.

## 12. Code should explain WHY, not just WHAT

Business rules must be documented in code.
A future developer reading the code should understand the reasoning behind each decision,
not just the mechanics.
