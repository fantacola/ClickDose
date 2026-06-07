# ClickPlan

**ClickPlan is a personal pen assistant for GLP-1 injection pens.**

It is not a click counter. It is not a dose calculator. It tells you one thing:

> **This week: 37 clicks.**

Everything else happens in the background.

---

> ⚠️ **Medical disclaimer**
>
> ClickPlan does not provide medical advice or dosing recommendations.
> All mg values are estimates calculated from click counts.
> Always verify your dose visually and consult your doctor for any dosing decisions.

---

## What it does

ClickPlan listens to your injection pen through the microphone, counts clicks automatically, and tracks how much medication is left in the pen. Based on the pen's total capacity and your current dose, it tells you exactly how many clicks to count each week — with no remainder at the end of the pen.

The app is designed for weekly use. Open it before your injection, count your clicks, save — done in under 30 seconds.

## Core features

- **Automatic click detection** via microphone (no Bluetooth required)
- **Pen calibration** for reliable click recognition on your specific pen
- **Weekly target clicks** — no manual calculation, no planning
- **Dose reduction assistant** — at new pen start, optionally reduce one step
- **Pen lifecycle tracking** — remaining clicks, doses left, pen reconciliation
- **Injection history** — local log of all doses (offline, no cloud)
- **German and English** interface

## What ClickPlan is not

- Not a medical device
- Not a weight or calorie tracker
- Not a cloud service
- Not a subscription

## Status

**v1.x — Active development (pre-release)**

Core features are working. Not yet published to the Play Store.
See [docs/ROADMAP.md](docs/ROADMAP.md) for planned milestones.

---

## Development setup

### Requirements

- Android Studio (Hedgehog or newer)
- Android SDK 35
- Java 11+
- A physical Android device or emulator (API 24+)

### Build

```bash
# Debug build
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Release build (requires signing config in gradle.properties)
./gradlew assembleRelease
```

### Signing (release)

Create `gradle.properties` (gitignored) with:

```
RELEASE_STORE_FILE=/path/to/your.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

The build will run unsigned if these properties are absent.

### Reset app data during development

```bash
adb shell pm clear com.clickplan.app
```

---

## Architecture

- Native Android (Java), single `Activity`, no fragments
- All UI built programmatically — no XML layouts
- `SharedPreferences` for all local persistence
- `AudioRecord` at 48 kHz for click detection
- No external dependencies

See [docs/VISION.md](docs/VISION.md) and [docs/PRINCIPLES.md](docs/PRINCIPLES.md) for product philosophy.

---

## Project structure

```
app/src/main/java/com/clickplan/app/
└── MainActivity.java        # Single activity, all UI and logic

docs/
├── VISION.md                # Product mission and philosophy
├── PRINCIPLES.md            # Core design principles
└── ROADMAP.md               # Planned milestones
```
