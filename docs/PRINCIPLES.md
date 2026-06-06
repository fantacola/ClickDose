# ClickPlan — Design Principles

1. **The user should never calculate clicks manually.**
   The app always provides the exact number of clicks for the current week.

2. **The user should never plan pen usage manually.**
   Planning is automatic based on pen strength, doses per pen, and remaining clicks.

3. **Dose changes happen only when starting a new pen, never mid-pen.**
   Changing dose mid-pen creates remainder. The app prevents this by design.

4. **The pen endpoint is the truth.**
   If ClickPlan expected 74 remaining clicks but the user reaches the pen stop after 69, the pen is considered empty and the app corrects its internal state. This is normal reconciliation, not an error.

5. **Request as little data as possible.**
   The app only needs: pen name, full dose mg, doses per pen, clicks per full dose. Nothing else.

6. **No unnecessary features.**
   No weight tracking, BMI, calorie tracking, food-noise scale, gamification, badges, community features, or complex statistics.

7. **Clicks are the source of truth. mg values are estimates.**
   All mg values are calculated from click counts. They are shown as guidance, never as authoritative medical values.

8. **Premium, calm, minimal UI.**
   Inspired by Apple Health and Oura. The segmented circular ring is the central visual identity. Dark theme, no clutter.

9. **Prepared for German and English.**
   All visible text uses string resources. Both languages are maintained in parallel.

10. **Offline-first.**
    All data stays on the device. No account required. No cloud dependency.
