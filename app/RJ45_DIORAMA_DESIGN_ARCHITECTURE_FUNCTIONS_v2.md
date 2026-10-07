# RJ45 Diorama — Android Design, Architecture, Behaviors & Functions (v2)

**Reference:** uploaded `MASTER(10).ino` (3,925 lines). **Status:** design and integration specification, **NOT** an instruction to flash firmware. Actual device behavior is not hardware-verified by this document. Preserve the currently working MASTER and SLAVE firmware.

## 1. Goal and boundaries

Build a visitor-friendly, museum-grade Android companion for the physical RJ45 Diorama. The app must explain the physical five-operation exhibit, present actual readings and trustworthy statuses, and eventually request operations without bypassing MASTER safety logic. **Current phase: design review, navigation, mockups, and Android-only UI work.** No firmware flashing, no live Firebase command writes, no BLE provisioning tests, and no relay/motor/servo activation until separately approved.

## 2. What the current MASTER(10).ino actually contains

- TFT state flow: WAITING → WELCOME → MAIN MENU; main options SIMULATION, DASHBOARD, RECORDING. Simulation branches into INDIVIDUAL (Tram, Street Lights, RGB, Wind, Drainage) or FULL SYSTEM. Physical buttons NEXT GPIO25, SELECT GPIO26, BACK GPIO13.
- PIR visitor detection, APDS9960 gestures, TCS34725 RGB sensing, DS3231 RTC, DHT environmental readings, PCF8591 AIN0 water and AIN2 flex, DFPlayer, ESP-NOW to two SLAVES.
- MASTER currently runs a **Wi-Fi SoftAP and local web server**, not a verified Firebase-integrated phone-hotspot provisioning system. The generated `RJ45_MASTER_FIREBASE_PROVISIONING.ino` is a separate unapproved prototype; do not assume its functionality exists in `MASTER(10).ino`.
- Street lighting currently uses RTC **18:00 ON / 06:00 OFF**. The proposal's 17:30 trigger is not the current implementation.
- Wind: when started, capture `windBaseline` from AIN2, command SLAVE2 fan ON, attempt `/mp3/0004.mp3`, compare absolute flex change to baseline, compute `windPercent` using `WIND_FULL_BEND_DELTA=5`, label CALM/BREEZE/STRONG, refresh fan command. This `5` is a code constant, **not a validated calibration**; readings may be noisy. The older comment about flex triggering the fan does not describe this newer routine.
- Drainage currently: **water >=50% → pump OFF and servo-warning command `CMD_GATE_OPEN` (comment says 45°)**; **water <45% → pump ON and servo-warning command `CMD_GATE_CLOSE` (0°)**. This is different from earlier 75% proposals and from a pump-ON-at-flood assumption. Verify physical intent, pump safety, and actual SLAVE1 servo angle before changing it. The existing command names still say `GATE`, even though the desired exhibit role is a flood warning signal.
- Recording is **sensor sampling to Serial Monitor** every ~2 seconds while Recording screen is active: time, temperature, humidity, water, flex, sample count. It is **not** microphone/audio recording and does not automatically play DFPlayer narration.
- DFPlayer: init/READY means serial module setup succeeded, **not** proof of audible output. Startup can play `/mp3/0001.mp3`; Serial Monitor `p` play track 1, `s` stop, `+`/`-` volume, `r` reinitialize. Wind attempts track 4. Track existence, volume, SD and speaker must be separately verified.
- Local web server and serial commands exist. Do not treat HTTP acceptance or ESP-NOW send as proof of physical completion.

## 3. Information architecture — Android

Five bottom navigation destinations, plus Device Setup in header/settings:

1. **Overview:** friendly connection banner; active operation; river/wind/temperature/humidity cards; safety alerts; last update; source and stale-state indicator. No invented metrics, visitor counts, or fake success.
2. **Simulation (primary):** segmented Individual / Full System; five operation cards with one-line purpose, real state, and detailed operation screen. Clear request/acknowledgment stages; separate Start and Stop with confirmations for safety-critical actions.
3. **Controls:** expandable Tram, Street Lights, RGB, Wind, Drainage, Audio. Distinguish **automatic** and **manual override**, with MASTER authorization, interlocks, and read-only/offline disable states. Do not offer controls that current MASTER cannot actually handle as if they work.
4. **Explore:** educational explanation of tram/river/wind/RGB and measured trends where historical data actually exists. PIR is motion-event sensing, **not unique visitor counting**. AI curator commentary only if genuinely implemented and based on valid inputs; otherwise mark future concept or omit.
5. **Logs:** real events with local timestamps, origin (TFT/app/automatic), request ID, acknowledged vs completed status, filters, offline/empty/error states. No fabricated histories.

## 4. Proposed visual system (requires high-fidelity mockup review)

- Nature/museum theme: background `#FBF9F1`, primary forest `#1E3F20`, moss `#2D5A27`, walnut `#705335`, river `#2563EB`, neutral cards white, accessible contrast.
- Hierarchy: prominent operation name, short plain-language description, one primary action, live physical status, expandable technical details.
- Bottom nav with icons and labels; large touch targets; 16dp spacing and rounded cards; avoid clutter, excessive badges, or raw sensor values on visitor-facing surfaces.
- Present visual mockups for **Overview, Simulation Hub, all five operation details, Full System, Controls, Explore, Logs, Device Setup**, and **offline, loading, pending, running, completed, failed, warning, empty** states.
- Design should adapt to typical Android phones and larger text settings. Mockups must show actual screens, not ASCII wireframes or unrelated inspiration images.

## 5. Operation-by-operation behavior and UI

### Tram — Gesture → Motor → Hall station
- Show gesture readiness, commanded direction, motor request state, Hall station arrival, and stop. Hall activation is the completion evidence when available.
- Do not assert Hall success solely because a motor command was sent. Provide STOP prominently; no auto-restart after reconnection.

### Street lights — RTC → street LEDs
- Show actual DS3231 clock and current scheduled mode. In `MASTER(10).ino`, schedule is 18:00–06:00; app must not silently assume 17:30.
- A virtual/demo RTC clock is a **future integration requirement**, not proven in this MASTER; do not alter physical RTC during demos. Present preview UI as disabled/not connected until supported.

### RGB — color sensor → WS2812B
- Show sensed color, sensor-tracking mode and optional manual palette. Manual vs automatic must be exclusive and synchronized between TFT, app and physical LEDs once implemented.
- Current firmware's existing color-scan behavior should be audited before enabling remote palette controls. Do not claim color changed until confirmed.

### Wind — fan → flex airflow response
- Start fan first, take pre-fan AIN2 baseline, measure delta; display CALM/BREEZE/STRONG and actual ADC/delta in expandable details. The source currently maps 5 ADC counts to 100%: not scientifically calibrated.
- Explain weak response as *insufficient measured flex movement*, not automatically fan failure. Stop must stop fan and owned audio. Avoid repeated relay chatter and require failsafe/offline shutoff design.

### Drainage — water level → pump + servo warning
- UI must faithfully label source behavior **>=50%: DANGER, pump OFF, warning command; <45%: SAFE, pump ON, warning reset**. Between 45% and 50%, prior state is retained (hysteresis). Whether this is the *desired* physical safety policy requires explicit owner approval.
- Servo lives on SLAVE1 GPIO4. MASTER calls `CMD_GATE_OPEN/CLOSE` and comments indicate 45°/0°, not a verified 90°. Do not invent 90° or rename on-device behavior without examining SLAVE1.
- Do not provide unguarded pump ON override. Consider dry-run prevention, maximum run duration and emergency stop as future verified safety requirements.

### Full System
- Represents enabling the five functional subsystems together; **not necessarily sequential steps 1/5...5/5**. Current MASTER's `UI_SIMULATION` enables multiple operation handlers; show independent live statuses, not invented linear progress.
- A stop action must respect MASTER interlocks and all affected SLAVE states. Verify final hardware state rather than assuming a sent stop succeeded.

## 6. TFT and Android interaction contract

- MASTER is authoritative for sensor measurements, simulation state, safety decisions, and reported device state.
- TFT buttons and physical sensors remain fully usable **without Android** or Internet. Android must reflect TFT-originated changes and must not overwrite local actions with stale cached app commands.
- Recommended lifecycle: `Requested → Accepted → Running → Completed`, or `Rejected / Failed / Timed out / Offline`; these are **target architecture**, not currently implemented end-to-end.
- On app disconnect: display stale readings as unavailable with last-seen time; disable controls. Never equate a Firebase write or ESP-NOW send with physical execution.
- Show explicit distinction between: connectivity to cloud, MASTER, SLAVE1, SLAVE2, and actuator state. Slave status needs real acknowledgment, not assumed `online=true`.

## 7. Planned architecture (NOT current hardware capability)

```text
Android UI (readings, simulation, controls, logs)
   ↕ authenticated Firebase RTDB client (future)
Firebase RTDB (telemetry, reported state, requests, acknowledgments, logs)
   ↕ authenticated MASTER bridge (future)
MASTER ESP32 (TFT, sensors, safety, command arbiter)
   ↕ ESP-NOW channel 1 + verified replies
SLAVE1: tram/Hall/servo       SLAVE2: RGB/street lights/fan/pump
```

Current `MASTER(10).ino` instead provides Wi-Fi **SoftAP/local HTTP** plus ESP-NOW. Transition to a **phone hotspot + Firebase** requires separately designing Wi-Fi channel coexistence, credential handling, authentication, replay protection, safe reconnect and physical validation. Hotspot must support 2.4 GHz and compatible channel **1** or the ESP-NOW design must be revised. A custom BLE GATT credential writer with hardcoded proof-of-possession is **not** approved secure provisioning. No plaintext passwords in logs or Firebase.

**Proposed data model (design only):**
- `/system`: heartbeat, firmware/version, actual MASTER/SLAVE link health, last-seen.
- `/telemetry`: measured temperature, humidity, water raw/percent, flex raw/baseline/delta, PIR pulses, sensor validity and timestamp.
- `/reported_state`: actual operation mode, per-subsystem state, TFT/app command origin, audio playback report, fault states.
- `/requests/{requestId}`: unique ID, origin, action, args, creation/expiry, authorization, execution state.
- `/logs/{eventId}`: actual timestamp, source, event type, outcome, request reference.
- Cloud authentication and database rules must prevent anonymous public actuator control. Offline queue must not replay stale motor/pump commands.

## 8. Recording and audio — clarify in the design

**Recording** means *telemetry/session logging*, not recording audio or automatically narrating. In UI label it **Sensor Session Log** or **Data Recording**, and show start/stop, sample count, interval, export/availability only if truly implemented. Current source prints to Serial, not cloud storage or downloadable audio. A new Android recording history would require an explicit logging pipeline.

**Audio** is separate: DFPlayer plays existing microSD tracks. `/mp3/0001.mp3` is startup/test track and `/mp3/0004.mp3` is attempted during Wind. A single DFPlayer can play only one track at once. Display READY vs PLAYING vs FAILED separately; READY alone does not confirm speaker output. Do not promise continuous background sound plus simultaneous narration.

## 9. Review checklist before implementation

**Design review (safe now):** produce high-fidelity mockups; clickable navigation plan; copy, statuses, empty/offline states; component tokens; verify UI descriptions against the MASTER source. No firmware edits or device commands.

**Source consistency review:** inspect actual SLAVE1 and SLAVE2 firmware for servo angles, Hall reports, relay failsafe, light command semantics, acknowledgments, fan relay and pump operation; check MASTER source for conflicting old comments and command implementations. Label unknowns explicitly.

**Android-only phase after design approval:** implement UI layouts, navigation and read-only states on the existing feature branch. Use explicitly labeled static **design previews only** in isolated previews; never display demo readings as live telemetry. No live Firebase writes or hardware commands.

**Later integration phases requiring separate permission:** authenticated cloud telemetry; verified real-time reporting; physical acknowledgments; safe one-by-one operation requests; BLE provisioning and hotspot/ESP-NOW tests; comprehensive fault testing. Never flash or overwrite working firmware without a backed-up known-good binary/source and rollback plan.

## 10. Antigravity handoff — exact task

1. Read this blueprint and `MASTER(10).ino` carefully; identify contradictions or unverified physical details.
2. **DO NOT** flash/edit MASTER or SLAVE firmware, change Firebase rules/schema, write live commands, or test hardware.
3. Produce high-fidelity **visual Android mockups**, including all five screens and operation detail pages, in the approved theme.
4. Show state diagrams for each operation, navigation and connectivity, distinguishing **current behavior** from **future planned behavior**.
5. Provide a mismatch/assumption table with evidence (file and function names), and seek explicit approval **before any Android implementation**.

**Approval gate:** User reviews mockups and architecture first. No implementation approval is implied by receipt of this document.
