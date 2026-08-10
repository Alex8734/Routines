# PLAN.md — Projekt "Routines" (Tasker-Äquivalent, Home-Assistant-Stil)

> **Status:** Planungsphase. Kein Produktionscode geschrieben (außer dem bereits vorhandenen Fundament aus Setup-Schritt 1–4). Warte auf explizites **"Go"**.
>
> **Product Owner / Lead Architect:** Claude (Opus)

---

## 0. Agenten-Taskforce & Rollen-Mapping

Der Marketplace `wshobson/agents` ist in dieser Umgebung **nicht installierbar** (`/plugin isn't available`). Die geforderten Tier-Rollen werden auf die lokal in `.claude/agents/` definierten Profile gemappt, die identische Verantwortlichkeiten tragen:

| Spezifikation (wshobson/agents) | Lokales Profil | Modell | Schreibzone |
|---|---|---|---|
| Lead Architect / Android Architect (Tier 1) | `software-architect` | Opus | nur Planung/Koordination, kein Code |
| Android System Pro & Linux Sysadmin (Tier 1/2) | `system-implementer` | Opus | `core/` |
| Compose Pro / Android UI Expert (Tier 3) | `ui-designer` | Sonnet | `ui/` |
| Kotlin Tester / QA Automator (Tier 3) | `qa-automator` | Sonnet | `src/test/`, `src/androidTest/` |

**Verification Gate:** Kein Feature gilt als fertig, bevor `qa-automator` grüne Tests dafür geliefert hat (CLAUDE.md-Regel).

---

## 1. Architektur-Zielbild (Clean Architecture)

```
ui/        (Presentation)  → Compose Screens, ViewModels (MVVM), reiner JSON-Übersetzer
  ↓ (State / Events)
domain/    (Domain)        → Use Cases, Modelle, Interfaces (Trigger/Action-Verträge)
  ↓
core/      (Core Engine)   → Foreground Service, SharedFlow-EventBus, Evaluator,
                             Trigger-Quellen, Action-Executoren, Root/Shell
  ↓
data/      (Data)          → Room DB (schlank: macro_id + configuration JSON), Repository
```

**Daten-Philosophie:** Ein Makro ist ein roher JSON-String. Die Room-DB hält **nur zwei Spalten**: `macro_id` (String, PK) und `configuration` (roher JSON-String). Keine komplexen Relationen. Die UI liest/zeigt/schreibt ausschließlich JSON.

**Laufzeit:** Persistenter Android **Foreground Service** (mit Notification) → wird vom System nicht gekillt. Alle System-Events fließen über einen zentralen **Kotlin `SharedFlow`** an einen **Evaluator**, der die JSON-Bedingungen prüft und passende Actions auslöst.

---

## PHASE 1 — Setup & Fundament

> Teilweise bereits erledigt (Setup-Schritte 1–4). Hier dokumentiert + Lücken ergänzt.

### Lead Architect
- [x] Gradle-Abhängigkeiten: `kotlinx.serialization`, Coroutines, Room, KSP (`libs.versions.toml`, `build.gradle.kts`)
- [x] Verzeichnisstruktur `core/`, `data/`, `ui/`
- [ ] `domain/`-Paket ergänzen (Use-Case-/Interface-Schicht für saubere Trennung)
- [ ] Architektur-Entscheidungen in `.claude/graphify.md` aktualisieren (Schema-Drift beheben, s. Risiken)

### System Implementer
- [x] `AndroidManifest.xml`: `RECEIVE_BOOT_COMPLETED`, `ACCESS_NETWORK_STATE`
- [x] `BootReceiver.kt` (Trigger `on_startup`) + Manifest-Registrierung
- [x] `NetworkStatusTracker.kt` (Trigger `sim_card_data_connected`, `TRANSPORT_CELLULAR`)
- [ ] Manifest um alle weiteren Permissions erweitern (s. Abschnitt "Permissions")

### Data Agent
- [x] `MacroScript.kt`, `Action`, sealed `Trigger`
- [x] `MacroEntity`, `MacroDao`, `AppDatabase`
- [ ] **Refactor `MacroEntity` auf das spezifizierte Schlank-Schema** (`macro_id` + `configuration`), redundante Spalten (`name`, `enabled`) entfernen — diese leben im JSON

### QA Automator
- [ ] Test-Infra einrichten (MockK, Turbine, kotlinx-coroutines-test, Robolectric falls nötig)
- [ ] Smoke-Test: JSON Round-Trip `MacroScript` ↔ String

---

## PHASE 2 — Core-Engine & JSON-Fundament

### Lead Architect
- [ ] **JSON-Script-Schema final spezifizieren** (versioniert, `schemaVersion`-Feld)
- [ ] Verträge definieren: `interface TriggerSource`, `interface ActionExecutor`
- [ ] Event-Modell für `SharedFlow` definieren (`SystemEvent`-Hierarchie)

### System Implementer (`core/`)
- [ ] **`MacroForegroundService`** — persistenter Foreground Service + Notification-Channel, hält Engine am Leben
- [ ] **`EventBus`** — zentraler `MutableSharedFlow<SystemEvent>` (replay/buffer konfiguriert)
- [ ] **`MacroEvaluator`** — konsumiert `SharedFlow`, matcht Events gegen aktive Makro-Trigger, dispatcht Actions
- [ ] **`MacroEngine`** — parst `configuration`-JSON → `MacroScript`, führt `actions` sequentiell aus, Fehlerisolation pro Action
- [ ] **Trigger-Registry** — registriert/deregistriert Trigger-Quellen dynamisch je nach aktiven Makros
- [ ] `on_startup`-Trigger an EventBus anbinden (BootReceiver → Service starten → Event emittieren)
- [ ] `sim_card_data_connected`-Trigger: `NetworkStatusTracker`-Flow → EventBus

### Data Agent (`data/`)
- [ ] `MacroRepository` — CRUD über DAO, mapped Entity ↔ roher JSON-String
- [ ] JSON-Serializer-Modul (`Json { ignoreUnknownKeys = true; classDiscriminator = "type" }`) zentral bereitstellen

### QA Automator (`src/test/`)
- [ ] `MacroEngineTest` — Parsing, sequentielle Ausführung, Parse-Error-Handling
- [ ] `MacroEvaluatorTest` — Turbine: Event rein → korrekte Action raus
- [ ] `EventBusTest` — SharedFlow-Emissionen, Backpressure
- [ ] Negative Tests: ungültiges JSON, unbekannter Trigger/Action-Typ

---

## PHASE 3 — Root / System-Features (Actions)

### Lead Architect
- [ ] Sicherheits-/Berechtigungs-Strategie für Root festlegen (Opt-in, klare User-Warnung)
- [ ] Fallback-Kaskade spezifizieren: Standard-API → Root-Shell → Accessibility

### System Implementer (`core/`)
- [ ] **`ShellExecutor`** — `ProcessBuilder`; Modus `USER` vs. `ROOT` (`su -c`); Timeout, stdout/stderr-Capture, Exit-Code
- [ ] **`RootChecker`** — prüft Root via `su`-Probe / Binary-Suche; gecached
- [ ] Action **`execute_shell_script`** — nutzt `ShellExecutor`, Param `runAsRoot: Boolean`
- [ ] Action **`toggle_hotspot`** — Standard-API-Versuch → Root-Fallback (`svc wifi tether`, `settings put`)
- [ ] Action **`fire_app_intent`** — `Intent`/Deep-Link/Shortcut starten, Package-Resolver, Fehler bei nicht installierter App
- [ ] Graceful Degradation: Permission-Denial / kein Root → strukturiertes `ActionResult.Failure`

### Data Agent
- [ ] JSON-Schema um Action-Typen `execute_shell_script`, `toggle_hotspot`, `fire_app_intent` erweitern (Params dokumentiert)

### QA Automator (`src/test/`)
- [ ] `ShellExecutorTest` — MockK über ProcessBuilder-Wrapper, USER/ROOT-Pfade, Timeout, Exit-Codes
- [ ] `RootCheckerTest` — Root vorhanden / nicht vorhanden
- [ ] `ToggleHotspotTest` — API-Erfolg, API-Block → Root-Fallback ausgelöst
- [ ] `FireAppIntentTest` — Intent korrekt gebaut, App-nicht-gefunden-Fehler

---

## PHASE 4 — Feature-Expansion (vollwertiges Tasker-Äquivalent)

> Jede Erweiterung folgt demselben Muster: neue `TriggerSource` bzw. `ActionExecutor` + Schema-Eintrag + Tests. Modular, ohne Engine-Änderung.

### Zusätzliche Trigger (System Implementer, `core/trigger/`)
- [ ] **`time_schedule`** — Uhrzeit / Intervalle / Cron (`AlarmManager` / `WorkManager`)
- [ ] **`battery_level`** — Über-/Unterschreiten X % (`BatteryManager` / Sticky-Broadcast)
- [ ] **`geofence`** — Betreten/Verlassen GPS-Koordinaten (`GeofencingClient`) — benötigt Location-Permissions
- [ ] **`wifi_ssid`** — WLAN-SSID verbunden/getrennt
- [ ] **`bluetooth_device`** — Bluetooth-Gerät gekoppelt/verbunden
- [ ] **`nfc_tag`** — NFC-Tag gescannt
- [ ] **`sensor_threshold`** — Sensor-Schwellenwerte (`SensorManager`)

### Zusätzliche Actions (System Implementer, `core/action/`)
- [ ] **`set_brightness`**, **`set_volume_profile`**, **`toggle_dnd`** (System-Settings; tw. `WRITE_SETTINGS`/Notification-Policy)
- [ ] **`http_request`** / **`webhook`** — HTTP senden (INTERNET-Permission)
- [ ] **`show_notification`** — System-Benachrichtigung (`POST_NOTIFICATIONS`)
- [ ] **`accessibility_click`** — Display-Klick-Simulation via **Accessibility Service** (Non-Root-Pfad)

### QA Automator
- [ ] Pro neuem Trigger/Action mind. 1 Unit-Test (Emission + Schema-Parse), Fehlerfälle

---

## PHASE 5 — UI-Ausbau (Jetpack Compose, `ui/`)

### UI Designer (`ui/`)
- [ ] **`MacroDashboardScreen`** — Liste aller Makros, An/Aus-Schnellschalter, Status
- [ ] **`MacroDashboardViewModel`** — `Flow<List<Macro>>.collectAsState()`, Toggle-Intent an Repository
- [ ] **`MacroEditorScreen`** mit zwei Tabs:
  - [ ] **Tab 1 — Visual Builder**: Dropdowns (Trigger/Action-Typ) + Textfelder → generiert JSON
  - [ ] **Tab 2 — Expert Scripting**: großes rohes JSON-Textfeld, Eintippen/Einfügen/Export
- [ ] **`MacroEditorViewModel`** — bidirektionale Sync Form ↔ JSON, Live-Validierung, Error-Marker
- [ ] Navigation (Dashboard ↔ Editor), Compose-Theme nutzen

> **Schichtregel:** Keine Business-Logik in `ui/`. Validierung/Parsing-Aufrufe gehen an `core/`/`domain/`.

### QA Automator (`src/androidTest/`)
- [ ] `MacroDashboardScreenTest` — Liste rendert, Toggle wirkt
- [ ] `MacroEditorScreenTest` — Tab-Wechsel, Form→JSON-Sync, ungültiges JSON zeigt Error-Marker

---

## PHASE 6 — QA-Absicherung & Härtung (Verification Gate)

### QA Automator
- [ ] Coverage ≥ 80 % für `core/`-Logik
- [ ] Integrationstest BootReceiver → Service-Start → Event
- [ ] Ende-zu-Ende: JSON-Makro `on_startup` + `execute_shell_script` (USER) Durchlauf
- [ ] Edge Cases: Permission denied, Timeout, Parse-Error, kein Root

### Lead Architect
- [ ] Architektur-Review gegen Schichtregeln (keine `ui/`-Logik, keine `core/`-UI)
- [ ] `graphify`-Knowledge-Graph final aktualisieren
- [ ] Release-Checkliste: alle Permissions deklariert, alle Tests grün → **Approve**

---

## Permissions (AndroidManifest.xml)

| Permission | Zweck | Phase |
|---|---|---|
| `RECEIVE_BOOT_COMPLETED` | `on_startup`-Trigger | 1 ✅ |
| `ACCESS_NETWORK_STATE` | `sim_card_data_connected` | 1 ✅ |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_*` | persistenter Service | 2 |
| `POST_NOTIFICATIONS` | Service-Notification, `show_notification` | 2/4 |
| `INTERNET` | `http_request` / Webhooks | 4 |
| `ACCESS_FINE_LOCATION` / `ACCESS_BACKGROUND_LOCATION` | Geofencing, WLAN-SSID-Auslesen | 4 |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | WLAN-SSID-Trigger, Hotspot | 4 |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | Bluetooth-Trigger | 4 |
| `NFC` | NFC-Tag-Trigger | 4 |
| `WRITE_SETTINGS` (Special) | Helligkeit/System-Settings | 4 |
| Notification Policy Access (Runtime) | DND togglen | 4 |
| Accessibility Service (Runtime, kein Manifest-Permission) | Klick-Simulation | 4 |
| `su` / Root | Shell, Hotspot-Fallback — **keine Permission, Runtime-Probe** | 3 |

---

## Risiken & offene Punkte

1. **Schema-Drift:** `.claude/graphify.md` beschreibt ein älteres Schema (`trigger: "BOOT"`-String), der vorhandene Code nutzt strukturierte sealed `Trigger`. → In Phase 1 vereinheitlichen, graphify aktualisieren.
2. **`MacroEntity` zu fett:** Aktuell `name`/`enabled`-Spalten; Spec verlangt nur `macro_id` + `configuration`. → Refactor Phase 1.
3. **Root nicht garantiert:** Alle Root-Actions brauchen sauberen Non-Root-Fallback / klare Fehlermeldung.
4. **Hotspot-APIs versioniert:** Verhalten je Android-Version unterschiedlich → robuste Fallback-Kaskade + Tests.
5. **Marketplace nicht verfügbar:** Rollen über lokale `.claude/agents/`-Profile abgebildet (s. Abschnitt 0).
