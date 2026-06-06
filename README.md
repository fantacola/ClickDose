# ClickPlan

ClickPlan is a click-based pen tracking and planning assistant for GLP-1 injection pens (Wegovy, Ozempic, Mounjaro and others).

It listens to your pen through the microphone and counts clicks automatically. Based on your pen's total clicks and your current dose plan, it tells you exactly how many clicks to count this week — with no remainder at the end of the pen.

> **ClickPlan does not provide medical advice or dosing recommendations.**
> All mg values shown are estimates calculated from click counts. Always verify your dose visually and consult your doctor for any dosing decisions.

## Features

- Automatic click detection via microphone
- Pen calibration for reliable detection
- Weekly target clicks with zero-remainder planning
- Injection history (local, offline)
- German and English interface

## Technical

- Native Android (Java), single Activity, no external dependencies
- Offline-first — all data stored locally via SharedPreferences
- Minimum SDK 24 (Android 7.0)
