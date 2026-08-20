# react-native-telecom-android

Android VoIP calling for React Native, built on Jetpack `core-telecom` and
CallStyle notifications. **It rings from a killed app.**

No iOS. CallKit is well served by other packages, and pretending one API covers
both platforms is how you end up with a module that is bad at each.

---

## Why this exists

The usual Android calling stack is `react-native-callkeep` — a
`ConnectionService` wrapper written for an Android that no longer exists. It
predates CallStyle notifications, predates the Android 14 full-screen intent
grant, predates foreground service types, and its self-managed phone account is
refused outright by several of the largest OEMs.

The failure it produces is not a crash. It is silence: a push lands on a locked
phone and nothing happens.

This package takes the opposite position on every one of those:

| | `callkeep` | this |
|---|---|---|
| System integration | `ConnectionService` | `androidx.core:core-telecom` |
| Ring UI | custom activity | `CallStyle` + full-screen intent |
| Ring path | JS headless task | native FCM service, no JS |
| Telecom refused by OEM | broken | degrades to notification-only |
| Decline from a killed app | nothing is sent | native webhook |
| Answered but never connected | rings forever | deadman ends it |
| "Why didn't it ring?" | guess | `getDiagnostics()` |

---

## Install

```sh
npm install react-native-telecom-android
```

Autolinked. Android only — the JS is importable on iOS, where every method is a
no-op and `isSupported` is `false`.

Requires the New Architecture (React Native 0.76+), `minSdk 24`, and
`@react-native-firebase/messaging` if you want the native push path.

### 1. Your call activity must show over the lock screen

The library's full-screen-intent activity turns the screen on and gets past the
keyguard, then hands off to yours. Yours has to be willing to be seen there:

```xml
<!-- android/app/src/main/AndroidManifest.xml -->
<activity
  android:name=".MainActivity"
  android:showWhenLocked="true"
  android:turnScreenOn="true"
  tools:node="merge" />
```

Add `xmlns:tools="http://schemas.android.com/tools"` to the `<manifest>` tag if
it is not there already.

### 2. Opt in to the native push path

This is the part that makes a killed app ring. Declare the service **in your
app's manifest** — the library deliberately ships it without an intent-filter,
so that it cannot silently race Firebase's own service for delivery:

```xml
<service
  android:name="com.telecomandroid.TelecomFirebaseMessagingService"
  android:exported="false"
  tools:node="merge">
  <intent-filter android:priority="1">
    <action android:name="com.google.firebase.MESSAGING_EVENT" />
  </intent-filter>
</service>
```

Leave `@react-native-firebase/messaging`'s service declared. The priority makes
ours win delivery; anything that is not a call is forwarded straight back to
theirs, so your existing background handler and token refresh keep working.

If you would rather not touch FCM at all, skip this and call
[`reportIncoming()`](#reportincoming) from your background handler instead. It
works — it is just the slower door, because JavaScript has to boot first.

### 3. Optional: a notification icon

Drop a white-on-transparent `ic_call_notification` in your drawables. Without
one the module uses the system call glyph. It does **not** fall back to your
launcher icon, because adaptive icons render as a white blob in the status bar.

---

## Send the push

A data-only, high-priority FCM message. Not a notification message — those are
drawn by the system and never reach your code when the app is dead.

```json
{
  "message": {
    "token": "…",
    "android": { "priority": "HIGH" },
    "data": {
      "rnTelecom": "incoming",
      "callId": "6812f0…",
      "callerName": "Aisha",
      "callerId": "user_88",
      "avatarUrl": "https://…/aisha.jpg",
      "callType": "audio",
      "roomToken": "…"
    }
  }
}
```

`rnTelecom`, `callId`, `callerName`, `callerId`, `avatarUrl`, `callType` and
`reason` are reserved. **Every other key is handed to your JS as `extra`** — put
your room id, channel token or session key there and it will be waiting for you
when the app comes up.

When the caller gives up, send the cancel:

```json
{ "rnTelecom": "cancel", "callId": "6812f0…", "reason": "remote_hangup" }
```

`android.priority: HIGH` is not optional. It is what buys the temporary
allowlist that lets a dozing app start a foreground service.

---

## Use it

```ts
import TelecomAndroid from 'react-native-telecom-android';

TelecomAndroid.configure({
  appName: 'Talkr',
  ringTimeoutMs: 45_000,
  answerTimeoutMs: 30_000,
  declineWebhook: {
    url: 'https://api.example.com/calls/{callId}/decline',
    headers: { Authorization: `Bearer ${token}` },
  },
});
```

Call it on **every** launch, early — including the launches caused by a push.
The options are persisted, because the process that needs them most is the one
your JS has never run in.

```ts
const sub = TelecomAndroid.addEventListener('answer', async ({ call }) => {
  await rtc.join(call.extra.roomToken);
  TelecomAndroid.reportConnected(call.callId);
});
```

### The cold start

This is the case every calling app gets wrong.

A push wakes a killed app, the user answers from the lock screen, and *then*
your JS boots. There was no listener to fire the `answer` event at. So do not
wait for one:

```ts
useEffect(() => {
  TelecomAndroid.getCurrentCall().then((call) => {
    if (call?.state === 'answering') joinAndReport(call);
  });
}, []);
```

`getCurrentCall()` is the source of truth. Events are a convenience for when
your app happens to already be running.

### The answer deadman

`reportConnected()` is not bookkeeping. From the moment the user answers, a
native timer is running; if media does not come up before `answerTimeoutMs`, the
call is torn down as `failed`.

That is deliberate. An answered call that never connects looks to the user like
your app broke, and it leaves an undismissable notification behind. Ending it is
the kinder outcome.

---

## API

| | |
|---|---|
| `configure(options)` | Persisted. Call on every launch. |
| `addEventListener(type, fn)` | Returns `{ remove() }`. |
| `getCurrentCall()` | `CurrentCall \| null`. The cold-start SSOT. |
| <a id="reportincoming"></a>`reportIncoming(payload)` | Ring for a push you received yourself. |
| `startOutgoing(payload)` | Registers the call with Telecom and the call log. |
| `answer(callId)` | The user accepted on *your* ring screen. No-op otherwise. |
| `reportConnected(callId)` | Media is up. Cancels the deadman. |
| `reportEnded(callId, reason)` | You ended it. |
| `setMuted` / `setHeld` / `setAudioRoute` | |
| `getAudioRoutes()` | What Telecom will actually route to, now. |
| `getDiagnostics()` | See below. |
| `openFullScreenIntentSettings()` | Android 14+ permission screen. |
| `requestBatteryOptimisationExemption()` | The one-tap dialog. |
| `openAutoStartSettings()` | `false` if this OEM has no such screen. |

**Events:** `incoming`, `answer`, `decline`, `ended`, `muteChanged`,
`holdChanged`, `audioRouteChanged`.

**End reasons:** `local_hangup`, `remote_hangup`, `declined`, `missed`,
`answered_elsewhere`, `failed`, `timeout`.

---

## Diagnostics

Every field here is a real, silent way an Android phone stops delivering calls.
None of them raises an error — the push lands and nothing happens, and the user
blames you.

```ts
const d = await TelecomAndroid.getDiagnostics();
// {
//   notificationsEnabled: true,
//   incomingChannelEnabled: true,
//   incomingChannelImportance: 'high',
//   canUseFullScreenIntent: false,   // ← Android 14 grant, not granted
//   ignoringBatteryOptimisations: false,
//   telecomRegistered: true,
//   hasAutoStartSettings: true,      // ← Xiaomi. Send them there.
//   manufacturer: 'Xiaomi',
//   sdkInt: 34,
// }
```

Worth putting behind a "calls not working?" link rather than only reading in a
bug report.

---

## Architecture

> Kept in sync with the code. Change the code, change the diagram.

### Component map

```
  ┌──────────────────────────────────────────────────────────────────┐
  │  JS  —  may not exist yet, may never exist for a given call      │
  │                                                                  │
  │   index.ts ── configure() ── addEventListener() ── getCurrentCall│
  └──────────────────────────────┬───────────────────────────────────┘
                                 │  JSON strings only, no codegen structs
  ┌──────────────────────────────▼───────────────────────────────────┐
  │  TelecomAndroidModule                       (the bridge, ~empty) │
  └──────────────────────────────┬───────────────────────────────────┘
                                 │
   FCM push ──► TelecomFirebaseMessagingService ──┐
                                                  │
   notification buttons ──► CallActionReceiver ───┤
                                                  │
   Telecom callbacks ─────────────────────────────┤
                                                  ▼
                                   ┌──────────────────────────┐
                                   │      TelecomEngine       │  ← the only door
                                   └────────────┬─────────────┘
                                                │  Intents
                                                ▼
                                   ┌──────────────────────────┐
                                   │       CallService        │  ← the state machine
                                   │   (foreground, phoneCall)│
                                   └──┬────┬────┬────┬────┬───┘
              ┌───────────────────────┘    │    │    │    └──────────────┐
              ▼                            ▼    │    ▼                   ▼
     ┌─────────────────┐        ┌─────────────┐ │ ┌────────────────┐ ┌──────────────┐
     │  Notifications  │        │   Ringer    │ │ │ TelecomSession │ │DeclineWebhook│
     │  CallStyle +    │        │  ringtone + │ │ │  core-telecom  │ │  native POST │
     │  full-screen    │        │  vibration  │ │ │  best-effort   │ │              │
     └─────────────────┘        └─────────────┘ │ └────────────────┘ └──────────────┘
                                                ▼
                                   ┌──────────────────────────┐
                                   │        CallStore         │  ← process-wide truth
                                   │  current call + event    │     outlives the bridge
                                   │  sink (null when no JS)  │
                                   └──────────────────────────┘

     ┌─────────────────┐  ┌──────────────┐  ┌──────────────────────┐
     │  TelecomConfig  │  │ Diagnostics  │  │ IncomingCallActivity │
     │  SharedPrefs    │  │ OemSettings  │  │ screen-on + keyguard │
     └─────────────────┘  └──────────────┘  └──────────────────────┘
```

### Cold start: killed app → ringing

```
  FCM data push   android.priority: HIGH      t=0
       │                                       │  (HIGH is what buys the
       ▼                                       │   temporary FGS allowlist)
  TelecomFirebaseMessagingService              │
       │  native. no JS runtime. no bundle.    │
       ▼                                       │
  TelecomEngine.handlePush()                   │
       │                                       │
       ▼                                       │
  CallService.onCreate ──► TelecomConfig.load()│  ← options from a previous
       │                     (SharedPreferences)│    process's configure()
       ▼                                       │
  CallService.startIncoming                    │
       │                                       │
       ├──► startForeground(phoneCall)         │
       ├──► CallStyle notification             │
       ├──► full-screen intent                 │
       ├──► Ringer.start()                     │
       ├──► TelecomSession.start()             ▼
       ├──► wake lock (ring window)         RINGS
       ├──► ring deadman armed (45s)
       ├──► CallStore.set(call)
       └──► emitIncoming() ──► DROPPED (no JS)

  ══════════════════════════════════════════════════════════
   compare:  callkeep would still be parsing the JS bundle here
  ══════════════════════════════════════════════════════════
```

### Answer → media

```
  user taps Answer on the lock screen
       │
       ▼
  CallActionReceiver ──► stale? (CallStore.get(callId) == null) ──► drop
       │
       ▼
  CallService.answer()
       │
       ├──► ring deadman cancelled
       ├──► Ringer.stop()
       ├──► state = answering,  answeredAt = now
       ├──► TelecomSession.answer()
       ├──► notification ──► CallStyle.forOngoingCall
       ├──► ANSWER DEADMAN ARMED (30s)  ◄────────────┐
       ├──► emitAnswer() ──► DROPPED (no JS yet)     │
       │                                             │
       ▼                                             │
  IncomingCallActivity                               │
       │  setShowWhenLocked + setTurnScreenOn        │
       │  requestDismissKeyguard                     │
       ▼                                             │
  host MainActivity  (needs showWhenLocked itself)   │
       │                                             │
       ▼                                             │
  React Native boots  ◄── FIRST TIME JS RUNS         │
       │                                             │
       ▼                                             │
  getCurrentCall() ──► { state: 'answering' }        │
       │                                             │
       ▼                                             │
  app joins media (Agora / LiveKit / …)              │
       │                                             │
       ▼                                             │
  reportConnected(callId) ─────────────────────────► CANCELS IT
       │
       ▼
  state = connected,  Telecom.setActive(),  chronometer starts
       │
       ▼
  FGS type upgraded: phoneCall ──► phoneCall | microphone [| camera]
       (only for permissions actually granted — an unheld claim is a
        SecurityException, and that would cost the service and the call)

  ── if reportConnected never comes ──────────────────────────
  30s elapse ──► end('failed') ──► notification cleared, call torn down
```

### State machine

```
      push / reportIncoming()              startOutgoing()
               │                                  │
               ▼                                  │
        ┌─────────────┐                           │
        │   ringing   │                           │
        └──┬───┬───┬──┘                           │
           │   │   │                              │
   answer  │   │   │  decline / cancel push /     │
           │   │   │  telecom onDisconnect        │
           │   │   └──────────────► declined ──┐  │
           │   │                               │  │
           │   └── ring deadman 45s ► missed ──┤  │
           │                                   │  │
           ▼                                   │  │
        ┌─────────────┐ ◄────────────────────────┘
        │  answering  │
        └──┬───┬──────┘
           │   │
           │   └── answer deadman 30s ──► failed ─┤
           │                                      │
   reportConnected()                              │
           │                                      │
           ▼                                      │
        ┌─────────────┐                           │
        │  connected  │ ◄──► held  (setHeld /     │
        └──┬──────────┘       telecom onSetInactive)
           │                                      │
           │  reportEnded() / hangup button /     │
           │  cancel push / telecom onDisconnect  │
           ▼                                      │
        local_hangup / remote_hangup / ───────────┤
        answered_elsewhere / timeout              │
                                                  ▼
                                            ┌──────────┐
                                            │  ended   │
                                            └────┬─────┘
                                                 │
                    declined | missed ──► DeclineWebhook.fire()
                    everything else ────► JS reports it (app is awake)
                                                 │
                                                 ▼
                              notification cancelled, service stopped,
                              CallStore cleared, Telecom disconnected
```

### Timers

```
  ┌──────────────┬──────────┬───────────────────┬──────────────────────────┐
  │ timer        │ default  │ armed at          │ fires                    │
  ├──────────────┼──────────┼───────────────────┼──────────────────────────┤
  │ ring deadman │ 45_000ms │ startIncoming     │ end('missed') + webhook  │
  │ answer       │ 30_000ms │ answer()          │ end('failed')            │
  │  deadman     │          │ startOutgoing()   │                          │
  │ wake lock    │ = above  │ ring / answer     │ auto-released            │
  │ notification │ = ring   │ setTimeoutAfter   │ OS clears a stale ring   │
  └──────────────┴──────────┴───────────────────┴──────────────────────────┘

   connected ──► both deadmen cancelled, wake lock released
                 (the audio stack keeps the CPU up from here)
```

### Foreground service types

```
  ┌─────────────┬──────────────────────────────────┬──────────────────────────┐
  │ phase       │ types claimed                    │ why                      │
  ├─────────────┼──────────────────────────────────┼──────────────────────────┤
  │ ringing     │ phoneCall                        │ holds no hardware yet    │
  │ answering   │ phoneCall                        │ media not up             │
  │ connected   │ phoneCall                        │                          │
  │             │ | microphone  (if RECORD_AUDIO)  │ keeps mic alive in bg    │
  │             │ | camera      (if CAMERA, video) │ keeps camera alive in bg │
  └─────────────┴──────────────────────────────────┴──────────────────────────┘

   permission not granted ──► that type is omitted, not claimed
                              (Android validates at startForeground and throws;
                               losing the service would lose the call)
```

### Every mutation funnels to one thread

```
  JS   setMuted / setHeld / setAudioRoute ─┐
  JS   reportConnected / reportEnded ──────┤
  notification  Answer / Decline / Hangup ─┤
  Telecom  onAnswer / onDisconnect /       ├──► TelecomEngine.send()
           onSetActive / onSetInactive ────┤          │
  FCM      cancel push ────────────────────┤          │ Intent
  timers   ring / answer deadman ──────────┘          ▼
                                              ┌───────────────┐
                                              │  CallService  │
                                              │  main looper  │
                                              └───────────────┘
                                          one state machine, one thread,
                                          no second write path to get wrong

  guard: TelecomEngine.send() drops the intent when CallStore.get() == null
         (startService from background with no running service throws)
```

### FCM routing

```
  TelecomFirebaseMessagingService.handleIntent(intent)
        │
        ├── extras["rnTelecom"] ∈ { "incoming", "cancel" }
        │        │
        │        ▼
        │   super.handleIntent ──► onMessageReceived ──► TelecomEngine.handlePush
        │                                                        │
        │                            ┌───────────────────────────┴────────────┐
        │                       "incoming"                                "cancel"
        │                            │                                        │
        │                   CallRecord.fromPush                     CallStore.get(callId)
        │                            │                                        │
        │                     callId present?                       ┌─────────┴────────┐
        │                     ├── no  ──► log + drop              in call            not in call
        │                     └── yes ──► ring()                     │                  │
        │                                                      end(reason)            drop
        │
        └── anything else
                 │
                 ├── RNFirebase service resolvable? ──► startService(forwarded intent)
                 │                                      (their handler + token refresh live)
                 └── not installed ─────────────────► super.handleIntent
```

### Ring admission

```
  ring(call)
      │
      ▼
  CallStore.get()
      │
      ├── same callId already ringing ──► drop
      │        (an FCM retry, or a push the app also reported itself —
      │         re-running would restart the ringtone and re-arm the deadman)
      │
      ├── a different call in progress ──► decline + DeclineWebhook.fire()
      │        (one call at a time, by design)
      │
      └── nothing in progress ──────────► startIncoming()
```

### JS lifetime vs. the call

```
  process ────────────────────────────────────────────────────────────────►

   killed      push    ring    answer    activity    RN boots    JS ready
     │          │       │        │          │           │           │
     ▼          ▼       ▼        ▼          ▼           ▼           ▼
     ├──────────────────────────────────────────────────────────────┤
     │  CallStore.events == null                                    │
     │  emitIncoming / emitAnswer / emitEnded  ──►  ALL DROPPED      │
     ├──────────────────────────────────────────────────────────────┤
                                                                    │
                                                    module.initialize()
                                                                    │
                                                                    ▼
                                                      ┌──────────────────────┐
                                                      │ CallStore.events set │
                                                      │ events reach JS      │
                                                      └──────────────────────┘
                                                                    │
                       getCurrentCall()  ◄─────────────────────────┘
                       the catch-up read. state, not replayed events.
                       this is the SSOT — events are a warm-app convenience.
```

### Degradation ladder

```
  notifications enabled?
    ├── no  ──► ✗ SILENT. nothing below matters. getDiagnostics() says so.
    └── yes
         │
  incoming channel importance HIGH?
    ├── no  ──► ✗ no heads-up, no full-screen. arrives in the tray.
    └── yes
         │
  canUseFullScreenIntent?          (Android 14+ grant)
    ├── no  ──► ~ heads-up banner only. no lock-screen call screen.
    └── yes
         │
  startForeground allowed?         (Doze / standby bucket / battery saver)
    ├── no  ──► ~ plain notification, no service. rings, but unprotected.
    └── yes
         │
  Telecom registered?              (OEM refuses self-managed accounts)
    ├── no  ──► ~ notification-only. no audio routing, no call log.
    └── yes ──► ✓ full: routing, endpoints, OS in-call state, call log

  ignoring battery optimisations?
    └── no  ──► pushes may not arrive at all. openAutoStartSettings() on OEMs.
```

### Config: written by JS, read without it

```
  configure(options)
        │
        ▼
  TelecomConfig.save() ──► SharedPreferences("rn_telecom_android")
                                    │
        ╳ process dies              │
                                    │
  push wakes a cold process         │
        │                           │
        ▼                           ▼
  CallService.onCreate ──► TelecomConfig.load()
                                    │
                          ┌─────────┴─────────┐
                     readable            unreadable / absent
                          │                    │
                       use it          TelecomConfig.default()
                                       (never throws — a default
                                        ring beats no ring)
```

### Threads

```
  ┌────────────────────┬────────────────────────────────────────────────┐
  │ main looper        │ CallService state machine, ALL mutations,      │
  │                    │ Handler deadmen, notification posts            │
  │ rn-telecom-io      │ avatar fetch (3s timeout), then hops to main   │
  │ rn-telecom-webhook │ decline POST                                   │
  │ Telecom dispatcher │ core-telecom callbacks ──► handler.post()      │
  │ FCM service thread │ handleIntent ──► startForegroundService        │
  │ JS thread          │ module methods ──► Intents (fire and forget)   │
  └────────────────────┴────────────────────────────────────────────────┘
```

### File map

```
  src/
   ├ NativeTelecomAndroid.ts  codegen spec — JSON strings, flat, no structs
   ├ types.ts                 the JSON contract, mirrored by CallRecord.kt
   └ index.ts                 Platform guard, one event parser, no state

  android/src/main/java/com/telecomandroid/
   ├ TelecomAndroidModule.kt  bridge. forwards intents, reads CallStore
   ├ TelecomAndroidPackage.kt autolinking entry
   ├ TelecomEngine.kt         the only door in
   ├ CallService.kt           the state machine ◄── everything happens here
   ├ CallStore.kt             process-wide call + event sink
   ├ CallRecord.kt            one call, its JSON, its Bundle, push parsing
   ├ TelecomConfig.kt         options, persisted for cold processes
   ├ TelecomSession.kt        core-telecom. never throws
   ├ Notifications.kt         channels, CallStyle, full-screen intent, avatar
   ├ Ringer.kt                ringtone + vibration, honours the ringer switch
   ├ CallActionReceiver.kt    answer / decline / hangup buttons
   ├ IncomingCallActivity.kt  screen-on + keyguard, then hands off
   ├ TelecomFirebaseMessagingService.kt   the fast door. opt-in
   ├ DeclineWebhook.kt        declined / missed, with no JS alive
   ├ Diagnostics.kt           why it didn't ring
   ├ OemSettings.kt           autostart + FSI + battery screens
   └ Logger.kt                adb logcat -s RNTelecomAndroid
```

---

## Deliberate limits

**One call at a time.** A second incoming call is declined, and the webhook
fires for it. Call waiting is a different product with its own UI, and faking it
with a map of calls mostly produces calls that cannot be ended.

**Telecom is best-effort.** Registering a self-managed phone account is refused
by several large OEMs, and on some builds the platform throws from inside
`addCall`. Every entry point degrades to a notification-only ring rather than
crashing. You lose audio routing and the call log; you keep the ring.
`getDiagnostics().telecomRegistered` tells you which one you got.

**The ringtone is not on the channel.** Notification channels freeze their sound
at creation, so an app that ships the wrong ringtone in v1 is stuck with it
until users reinstall. The channel is created silent and high-importance, and
`Ringer` owns the sound — which also means `ringtoneUri` works at runtime, and
that silent and vibrate modes are honoured properly. Pass `ringtoneUri: ''` to
ring silently if your JS plays its own.

**The decline webhook is fired for `declined` and `missed` only.** Those are the
two endings that can happen with no JavaScript alive. Everything else happened
in an app that was awake and can report it with the auth and retries it already
has.

**Foreground start can still be refused.** Doze, an app-standby bucket or a
battery saver can take away the allowlist a high-priority push grants. When that
happens the module posts the notification without the service rather than
throwing: a degraded ring beats no ring.

---

## Versions

Built against `androidx.core:core-telecom:1.0.0`, React Native 0.86, Kotlin
2.0.x, `compileSdk 36`. `core-telecom` is guarded to API 26+; everything else —
the foreground service, CallStyle, the full-screen intent — works from 24.

## License

MIT
