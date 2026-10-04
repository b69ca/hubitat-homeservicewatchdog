# Home Service Watchdog

Home Service Watchdog is a local Hubitat driver that detects silent automation and service failures. Expected sources periodically call `checkIn()`. The driver tracks each source independently and exposes `healthy`, `late` and `failed` states through ordinary Hubitat capabilities, attributes and button events.

It makes no network requests and needs no account, API key, library or companion service.

## What can it watch?

Anything capable of calling a Hubitat device command:

- A critical Rule Machine rule that should run on a schedule.
- A Node-RED, Home Assistant or webCoRE flow.
- A LAN bridge or integration with a periodic success path.
- A backup, synchronization or notification job.
- A Raspberry Pi or server using Maker API.
- A daily safety routine whose absence matters more than its individual device events.

The watchdog detects that a source stopped reporting. It does not attempt to decide why the source stopped.

## Install

1. In Hubitat, open **Drivers Code → New Driver**.
2. Import the [raw driver](https://raw.githubusercontent.com/b69ca/hubitat-homeservicewatchdog/main/HomeServiceWatchdog.groovy) and click **Save**.
3. Open **Devices → Add Device → Virtual**.
4. Select **Home Service Watchdog** and name the device.
5. Enter the expected source names and timing thresholds.
6. Click **Save Preferences**.

The device stays in `setup` until preferences are saved. A configurable startup grace period prevents immediate alarms while automations and integrations reconnect.

## Basic setup

With one source named `Primary automation` and the defaults:

1. Have the monitored automation call **Check In** at least once every five minutes.
2. Leave the source argument blank or pass `Primary automation`.
3. At five minutes without a check-in, the watchdog becomes `late`.
4. At ten minutes, it becomes `failed`.
5. The next successful check-in returns it to `healthy`.

Source matching ignores capitalization and repeated spaces. Display names retain their configured capitalization.

## Watch states

| State | Meaning |
| --- | --- |
| `setup` | Preferences have not been saved. |
| `starting` | The initialization grace period is active. |
| `healthy` | Every source checked in before its late deadline. |
| `late` | At least one source crossed its late deadline, with none failed. |
| `failed` | At least one source crossed its failure deadline. |
| `snoozed` | Evaluation is temporarily paused. Check-ins are still recorded. |
| `error` | Source names or timing preferences are invalid. |

## Standard capabilities

| Capability | Healthy, starting or snoozed | Late | Failed |
| --- | --- | --- | --- |
| Contact Sensor | `closed` | `open` | `open` |
| Presence Sensor | `present` | `present` | `not present` |

This makes simple rules possible without custom-attribute support:

- Contact opens: a monitored source needs attention.
- Presence becomes not present: a failure deadline was crossed.
- Presence becomes present: no source is currently failed.

Configuration errors keep contact closed and presence present. Use `watchState = error` or `lastError` to identify setup problems without generating a false service outage.

## Automation events

| Button | Event |
| --- | --- |
| 1 | All sources recovered to healthy. |
| 2 | The watchdog became late. |
| 3 | One or more sources failed. |
| 4 | A configured continuing-failure reminder. |

`notificationText` provides a ready-to-send description and `notificationType` identifies `recovery`, `late`, `failure`, `reminder` or `test`.

Each button has a test command. Test events start with `TEST:` and do not modify check-in history or watchdog health.

## Commands

| Command | Purpose |
| --- | --- |
| **Check In** | Records a successful heartbeat for one configured source. |
| **Check In All** | Records the same heartbeat time for every configured source. |
| **Snooze** | Suspends late/failure evaluation for 1–1,440 minutes. |
| **Resume** | Ends a snooze and immediately evaluates all source ages. |
| **Reset Watchdog** | Clears check-ins and begins a new startup grace period. |
| **Refresh** | Evaluates current source ages immediately. |
| **Clear History** | Clears failure count and last failure duration. |
| **Initialize** | Rebuilds scheduling and begins a startup grace period. |

When only one source is configured, **Check In** accepts a blank source argument. Multiple sources require a configured name. Unknown names are rejected and reported in `lastError`; they do not silently create new monitored sources.

## Preferences

| Preference | Default | Purpose |
| --- | --- | --- |
| Expected sources | `Primary automation` | One name per line, maximum eight. |
| Late after | 5 minutes | First missed-check-in state. |
| Failed after | 10 minutes | Confirmed failure state; must exceed the late time. |
| Startup grace | 10 minutes | Holds alerts after initialization. |
| Evaluation interval | 1 minute | 1, 2, 5 or 10 minutes. |
| Emit late event | On | Button 2 on transition to late. |
| Emit recovery event | On | Button 1 when all problems clear. |
| Failure reminder | Never | Optional button 4 every 5 minutes to 3 hours. |
| Debug logging | Off | Automatically returns to off after 30 minutes. |

Deadlines are evaluated on the configured interval, so transitions can occur up to one evaluation interval after the exact threshold. Calling **Check In**, **Check In All**, **Resume** or **Refresh** evaluates immediately.

## Attributes

### Current health

| Attribute | Meaning |
| --- | --- |
| `watchState` | Overall state described above. |
| `configuredSources` | Number of expected sources. |
| `healthySources`, `lateSources`, `failedSources` | Current per-state counts. |
| `sourceStatus` | Each source’s state and whole-minute age. |
| `problemSources` | Comma-separated late and failed source names. |
| `conditionSummary` | Compact dashboard-friendly summary. |
| `secondsToNextDeadline` | Time until the next late/failure boundary or grace/snooze end. |
| `lastError` | Configuration or rejected check-in detail. |

### Timing and history

| Attribute | Meaning |
| --- | --- |
| `lastCheckIn`, `lastCheckInSource` | Most recently accepted heartbeat. |
| `lastEvaluated` | Latest health calculation. |
| `failureSince`, `failureSinceEpoch` | Start of the current failure. |
| `failureDurationMinutes` | Current failure duration. |
| `lastFailureMinutes` | Most recently recovered failure duration. |
| `totalFailures` | Failure transitions since installation or Clear History. |
| `snoozedUntil`, `snoozedUntilEpoch` | Active snooze end time. |
| `notificationText`, `notificationType` | Context for the latest button event. |

## Rule Machine examples

### Watch a scheduled rule

At the end of the monitored rule’s successful action path:

1. Add a **Custom Action** against the Home Service Watchdog device.
2. Select the `checkIn` command.
3. Pass the exact configured source name, such as `Nightly safety routine`.

Place the check-in after the important work. A check-in at the beginning only proves that the rule started.

### Local failure indicator

1. Trigger when watchdog presence becomes `not present`.
2. Turn on a local warning lamp or indicator.
3. Trigger when presence becomes `present` and turn it off.

### Notifications

Trigger on pushed buttons:

- Button 2: send a low-priority late warning.
- Button 3: send or announce `notificationText` as a failure.
- Button 4: repeat the active failure warning.
- Button 1: send a recovery notice.

## Maker API example

Expose the watchdog device through Hubitat Maker API, then call its `checkIn` command from a trusted system on your network. Maker API provides the endpoint format and access token for the selected device.

Use the source name as the command argument and URL-encode spaces and punctuation. Protect the Maker API token as a credential, restrict network access appropriately, and avoid putting it in public logs or repositories.

The watchdog itself never stores or transmits that token.

## Snoozing and maintenance

Use **Snooze** before planned server maintenance or automation changes. Check-ins received during the snooze are recorded, but late and failed states are not published. **Resume** immediately reconciles the current ages, which may produce a late or failed state if no fresh check-in arrived.

Initialization also creates a grace period. Unlike snooze, the grace period is intended for hub startup and driver configuration.

## Operational notes

- Configure a failure threshold comfortably longer than the source’s normal check-in interval and expected scheduling jitter.
- A five-minute source with a ten-minute failure threshold tolerates one missed run; adjust this for the consequence of false alarms versus delayed detection.
- A check-in proves that its caller reached that code path. It does not prove every downstream action succeeded unless the caller only checks in after verifying success.
- Persisted check-in times survive driver initialization. The grace period gives sources time to replace old heartbeats before evaluation resumes.
- All source names, timestamps and history stay on the hub.

## Development

Run the simulated Hubitat suite with Groovy 2.4.21:

```sh
groovy tests/HomeServiceWatchdogTest.groovy
```

The suite covers setup gating, startup grace, schedules, case-insensitive names, late and failure transitions, reminders, multi-source recovery, snooze/resume, unknown sources, test events, single-source shorthand, invalid configuration and JSON-safe state. GitHub Actions runs the same suite.

Source is available under the MIT License.
