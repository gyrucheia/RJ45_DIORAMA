# RJ45 Diorama — Android App Correction & Integration Contract

**Status:** Required review and correction guide, **not** a claim of tested hardware integration.  
**Audience:** Antigravity AI IDE and project developers.  
**Working branch:** `feature/rj45-firebase-integration`  
**Project:** Existing `SanLoMobApp` Android (Java) application. **Do not create a new app.**

## 1. Purpose: What the owner actually wants

The existing Android application was a **draft**, not the approved final product. Redesign it into a simple exhibit companion that **shows the physical diorama's real state** and **requests real operations** through the MASTER. The application must not feel like a developer command console.

**Primary experience:**
1. **Connect Diorama:** first-time setup over authenticated BLE; provision Wi-Fi if needed. Afterward use Wi-Fi/Firebase for routine monitoring and commands.
2. **Overview:** instantly see whether the MASTER and SLAVES are genuinely online, current sensor readings, alerts and current simulation.
3. **Simulation:** run one of five automatic demonstrations or Full System Simulation with meaningful feedback.
4. **Controls:** optional manual overrides, clearly separated from simulation.
5. **Explore / Logs:** PIR-based activity analysis, river alerts and genuine timestamped history.

No arbitrary manual command should be necessary just to demonstrate a physical operation. Never represent a Firebase write as successful actuation.

## 2. Physical architecture (source of truth)

```text
Android app <-- Wi-Fi/Internet --> Firebase Realtime Database
                                      ^
                                      | authenticated TLS Wi-Fi
                                      v
                           MASTER ESP32 (authoritative)
                          / TFT, buttons, sensors, RTC,
                         /  gesture, DFPlayer, simulation
                     ESP-NOW (existing channel 1)
                      /                    \
               SLAVE 1                    SLAVE 2
           tram motor, Hall,         RGB strip, street LEDs,
           SG90 servo GPIO4          fan relay, pump relay
```

- SLAVE 1: MAC `D4:E9:F4:A3:50:A8`.
- SLAVE 2: MAC `D4:E9:F4:E3:4E:2C`.
- Existing MASTER-to-SLAVE ESP-NOW packet is `struct CommandPacket { uint8_t command; int16_t value; };` (4 bytes in the existing protocol). Inspect the real firmware before changing any commands.
- Do not replace working firmware with a minimal reference sketch. Existing ESP-NOW, TFT navigation, audio and safety behavior must be preserved.

## 3. Five simulation operations — approved semantics

| Mode | Physical cause and effect | UI should display |
|---|---|---|
| **Tram** | Gesture → tram motor → Hall sensor | Running/stopped, Hall detection and genuine feedback |
| **Street Lights** | DS3231 RTC or virtual demo time → LEDs | Real/demo mode, effective time, actual light state |
| **RGB Lighting** | TCS34725 sensor **or** TFT/app manual color → WS2812B LEDs | Sensor/manual mode, selected and confirmed color |
| **Wind** | **Fan runs first → airflow physically bends flex sensor** (PCF8591 AIN2) | Fan state and measured flex response; no flex→fan feedback loop |
| **Drainage** | Water sensor (PCF8591 AIN0) → pump **and SG90 warning signal** | Water level, warning state, pump state, servo warning indicator |

**Important servo correction:** Owner confirms the existing **one SG90 servo on SLAVE 1 GPIO4** is now intended as the **drainage flood warning signal**, not a drainage gate. Tram is strictly gesture→motor→Hall. The proposal of servo **0° = normal / 90° = warning** came from Antigravity's implementation report; verify mechanical end stops and safe angles before physical testing. Inspect legacy gate commands and their effects before reassigning them. No second servo is assumed.

**Full System Simulation:** coordinate all five behaviors, but never run unsafe or mutually conflicting commands. Mode transitions, timeouts, and STOP behavior are MASTER responsibilities. Do not fabricate progress stages.

## 4. App navigation and screen requirements

### A. Overview (landing screen)
- Big, clear connection status: **Offline / Connecting / Online / Stale / Error** based on fresh verified heartbeat, not `master_online` alone.
- Compact, readable cards: temperature, humidity, water level, PIR motion, flex response, tram, street LEDs, RGB, fan, pump, warning servo.
- Prominent **Run Simulation** action and **Connect Diorama** setup access. Setup should not be disguised as a normal online status indicator.
- Unknown values appear as `--`; do not generate mock sensor values in production.

### B. Simulation (primary feature)
- Choose **Individual** or **Full System**.
- Individual offers five clearly named cards with a one-sentence explanation and **Start** action.
- On running, show actual reported mode, status, relevant sensor response, Stop, and error messages.
- Use lifecycle states **Requested → Accepted → Running → Completed** or **Rejected / Failed / Timed Out / Offline** as supported by actual acknowledgments. Do not invent completion.
- Physical TFT and app must reflect MASTER's same authoritative mode.

### C. Controls (optional)
- Tram Forward/Reverse/Stop; RGB sensor/manual mode and color; real/demo clock; safe fan/pump controls; voice-guide requests.
- Show controls only where hardware/firmware actually supports them. Disable unsupported controls and explain why.
- Safety rules and physical controls take precedence. MASTER may reject app requests.

### D. Explore
- PIR activity is **motion/event detection**, not unique-person identification or reliable visitor counting.
- Show actual activity trends and river-level alerts. AI summaries must be honestly labeled and require real supporting data.

### E. Logs
- Timestamped real events: requests, acknowledgments, simulation changes, alerts, connection problems, hardware feedback.
- Distinguish **request sent** from **actuator confirmed**.

**Design direction:** natural cream background, forest green, earthy brown, restrained accent colors; legible labels and status, not excessive switches or debug text. UI must work on ordinary Android phones, handle accessibility and different screen sizes.

## 5. BLE Wi-Fi setup: intended behavior versus unverified implementation

**Desired:** App discovers the nearby physical MASTER over BLE, authenticates it, securely transfers a user-selected **2.4 GHz SSID/password**, and waits until MASTER reports fresh verified Firebase connectivity. The phone running the app may also provide a hotspot, but Android generally cannot assume it can read that hotspot's password or detect its own hotspot as a currently joined Wi-Fi network. Provide manual SSID entry.

**Observed in uploaded Android ZIP:** `DioramaBleProvisioner.java` and `DioramaSetupDialogFragment.java` exist; the reported implementation uses custom `FFF0`-family GATT service, fixed PoP `RJ45-2026`, and direct JSON writes. This is **not evidence of interoperability with actual ESP32 firmware** and **not equivalent to secure official ESP-IDF provisioning**. No verified MASTER `.ino` was established from the uploaded ZIP. Treat BLE provisioning as **prototype / not hardware-validated**.

**Correction requirements:**
- Prefer an established, mutually compatible ESP32 BLE provisioning protocol with authenticated encrypted transport and per-device or securely provisioned proof-of-possession.
- No globally hardcoded PoP in public app code; no Wi-Fi passwords in logs, Firebase, or source control.
- Provide permission-denied, Bluetooth-off, device-not-found, pairing/authentication-failed, Wi-Fi-failed, channel-mismatch, Firebase-unavailable and retry states.
- Provisioning mode must have a deliberate entry condition and timeout. Avoid an indefinite open BLE configuration service.
- BLE GATT write success is **not** Wi-Fi join success. Verify MASTER state and fresh heartbeat with a bounded timeout.
- Do not prefill `Jamaica_Hotspot` or any assumed real network.

## 6. Wi-Fi + ESP-NOW coexistence (blocking hardware constraint)

MASTER uses one 2.4 GHz Wi-Fi radio for station connectivity and ESP-NOW. **Joining a phone hotspot on a channel other than the existing ESP-NOW channel 1 can break SLAVE communication.** A 2.4 GHz hotspot is not necessarily channel 1; many phones do not expose manual channel control. BLE setup does not solve this constraint.

**Before firmware implementation:** measure the hotspot's actual channel, identify the ESP32's configured peer/channel behavior, and demonstrate a compatible plan. If it cannot be made compatible safely, propose a separate Wi-Fi gateway or explicitly planned channel migration with controlled changes to all nodes. **Do not change SLAVE channels opportunistically.**

## 7. Firebase contract and security

Firebase project `sanlo-139ce`; database `https://sanlo-139ce-default-rtdb.asia-southeast1.firebasedatabase.app`; Android package `com.example.sanlomobapp`.

Conceptual paths:

```text
/system/master_heartbeat
/system/{master_online,slave1_online,slave2_online}
/telemetry/{temperature,humidity,water_raw,water_level_pct,flex_raw,pir_active,timestamp}
/reported_state/{simulation,tram_state,hall_detected,street_lights_mode,street_lights_on,
                 effective_hour,effective_minute,rgb_mode,rgb_color_hex,fan_state,
                 pump_state,warning_servo_state,last_updated}
/requests/{requestId}          # unique immutable command request
/acknowledgments/{requestId}   # MASTER-owned status and reason
/logs/{eventId}                # trusted event writer
```

The paths above are **design proposals**; compare them to the existing Android model and Firebase schema before migration. Do not assume existing data has been converted.

**Rules:** Operator authentication and authorization for command creation; MASTER-only telemetry, authoritative state, and acknowledgments; logs must not be writable by arbitrary authenticated clients. Anonymous sign-in by itself is **not sufficient authorization** for actuator control. MASTER credentials must be provisioned separately without embedding administrator keys or a shared database secret. Implement schema validation, per-command IDs, expiry, deduplication, safe allowed ranges, acknowledgement reasons, and stale-heartbeat handling. The app may show request accepted only after actual MASTER acknowledgement.

## 8. Safe development phases and hard stop points

**Phase A — Audit and UI correction (NOW)**
1. Inspect actual source files in `SanLoMobApp.zip`, project documentation, and current Git diff.
2. Produce an **existing screen → approved screen** mapping, showing which draft elements are retained, changed, or removed.
3. Show screen previews or layout plans before implementing more features.
4. Fix misleading command statuses, hardcoded hotspot assumptions, and BLE success messages in Android.
5. Run `gradlew assembleDebug` and report exact outcome; build success does not validate hardware.

**Phase B — Protocol and security review (NO FLASHING)**
1. Request actual *working* MASTER `.ino` and Antigravity-generated `RJ45_MASTER_FIREBASE_PROVISIONING.ino` if not available.
2. Diff and audit: existing features, pins, packets, timers, memory, BLE provisioning protocol, authentication, channel behavior, safety and servo ownership.
3. Verify hotspot channel and Firebase authorization design.
4. Produce risk assessment and rollback plan. Stop for owner approval.

**Phase C — Incremental hardware tests (ONLY AFTER APPROVAL)**
1. Verify baseline MASTER↔SLAVE ESP-NOW first.
2. Add Wi-Fi connectivity without changing actuators; test hotspot/channel behavior.
3. Add authenticated **read-only telemetry publishing**; verify dashboard heartbeat and offline state.
4. Verify BLE setup end-to-end on physical phone and ESP32, with failure/retry cases.
5. Add request/ack state handling and limited safe commands one at a time.
6. Test TFT/app RGB sync, demo RTC, fan→flex, drainage warning servo, pump, tram, and Full System Simulation with physical safety checks.
7. Test battery power, voltage under load, reconnect behavior and rollback.

**Prohibitions:** Do not flash generated reference firmware as a replacement for the working MASTER; do not change SLAVE firmware, merge/push Git, invent readings, claim tests passed without physical evidence, or enable live actuator commands during UI-only tests.

## 9. Acceptance criteria

- A first-time user can understand **Connect**, **Run Simulation**, and **Manual Controls** without learning ESP-NOW, GPIOs or Firebase request paths.
- The app never says **Connected** on BLE write success alone, or **Completed** on a Firebase request write alone.
- Offline/stale readings are visibly unavailable, not made up.
- Physical TFT and Android report the same confirmed MASTER-owned simulation/RGB/demo-clock state.
- Wind confirms fan-induced flex response; drainage servo is only the warning indicator, subject to physical testing.
- Existing ESP-NOW and SLAVE behavior remain functional throughout Wi-Fi integration.
- Access control prevents unauthorized remote activation.
- Every build and hardware test has a reproducible result and clear failure/rollback path.

## 10. Immediate instruction to Antigravity

> Read this document and `RJ45_DIORAMA_SYSTEM_CONTEXT (1).md` together. Where the older document conflicts with **owner-confirmed** hardware facts in this document (wind fan→flex; one servo reassigned to drainage warning), use the newer confirmed facts, but flag any mismatch with actual code and physical wiring. First deliver the app UI/navigation audit, proposed simple screen previews, BLE/Firebase interoperability gaps, and precise file-by-file plan. **Do not implement new firmware or flash anything yet.** Ask for approval before proceeding.
