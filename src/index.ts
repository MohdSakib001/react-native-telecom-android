import { NativeEventEmitter, Platform } from 'react-native';
import type { Spec } from './NativeTelecomAndroid';
import type {
  AudioRoute,
  CallEndReason,
  CurrentCall,
  Diagnostics,
  IncomingCallPayload,
  OutgoingCallPayload,
  TelecomEvent,
  TelecomEventType,
  TelecomOptions,
} from './types';

export * from './types';

/**
 * `getEnforcing` throws at import time when the module is missing, and this
 * package is Android-only by construction, so the require is guarded rather
 * than hoisted. Importing on iOS gives you an object whose every method is a
 * no-op — which is the correct behaviour for a package that has nothing to say
 * about CallKit.
 */
const native: Spec | null =
  Platform.OS === 'android' ? (require('./NativeTelecomAndroid').default as Spec) : null;

export const isSupported = native !== null;

const EVENT_NAME = 'RNTelecomAndroid';

const emitter = native ? new NativeEventEmitter(native as never) : null;

export type Subscription = { remove(): void };

type Handler<T extends TelecomEventType> = (
  event: Extract<TelecomEvent, { type: T }>,
) => void;

/**
 * Native sends one event name carrying a JSON string, and this is the only
 * place it is parsed. One shape, one parser — same reason the module's methods
 * take JSON: nothing here should be able to break because a codegen struct
 * marshalled differently between two React Native versions.
 */
export function addEventListener<T extends TelecomEventType>(
  type: T,
  handler: Handler<T>,
): Subscription {
  if (!emitter) return { remove() {} };

  const sub = emitter.addListener(EVENT_NAME, (raw: string) => {
    let event: TelecomEvent;
    try {
      event = JSON.parse(raw) as TelecomEvent;
    } catch {
      return;
    }
    if (event.type === type) handler(event as Extract<TelecomEvent, { type: T }>);
  });

  return { remove: () => sub.remove() };
}

/**
 * Must be called once, early, on every launch — including launches caused by a
 * push, because native persists these options and reads them back from a cold
 * process where JS has not run yet. Calling it late does not break the current
 * ring; it just means that ring used the previous launch's settings.
 */
export function configure(options: TelecomOptions): void {
  native?.configure(JSON.stringify(options));
}

/**
 * The SSOT for a cold start.
 *
 * When a push wakes a killed app and the user answers from the lock screen, no
 * event was ever delivered to JS — there was no JS. Read this on mount instead
 * of waiting for an `answer` event that already happened.
 */
export async function getCurrentCall(): Promise<CurrentCall | null> {
  const raw = await native?.getCurrentCall();
  return raw ? (JSON.parse(raw) as CurrentCall) : null;
}

/**
 * Ring for a push that arrived somewhere else — typically the
 * `@react-native-firebase/messaging` background handler, if you would rather
 * keep your existing messaging service than hand FCM over to this package.
 *
 * It works, but it is the slower door: the JS runtime has to boot before the
 * phone makes a sound.
 */
export function reportIncoming(payload: IncomingCallPayload): Promise<void> {
  return native ? native.reportIncoming(JSON.stringify(payload)) : Promise.resolve();
}

/** Tells Telecom and the system call log that a call is going out. */
export function startOutgoing(payload: OutgoingCallPayload): Promise<void> {
  return native ? native.startOutgoing(JSON.stringify(payload)) : Promise.resolve();
}

/**
 * The user accepted from your own in-app ring screen.
 *
 * The notification and lock-screen buttons answer natively and do not need
 * this. It is a no-op unless the call is still ringing, so calling it on every
 * accept path is safe — you never have to know which one got there first.
 */
export function answer(callId: string): void {
  native?.answer(callId);
}

/**
 * Media is up. Call this the moment your SDK reports the channel joined —
 * until it lands, the answer deadman is counting, and it will end the call.
 */
export function reportConnected(callId: string): void {
  native?.reportConnected(callId);
}

export function reportEnded(callId: string, reason: CallEndReason): void {
  native?.reportEnded(callId, reason);
}

export function setMuted(callId: string, muted: boolean): void {
  native?.setMuted(callId, muted);
}

export function setHeld(callId: string, held: boolean): void {
  native?.setHeld(callId, held);
}

export function setAudioRoute(callId: string, route: AudioRoute): void {
  native?.setAudioRoute(callId, route);
}

export async function getAudioRoutes(): Promise<AudioRoute[]> {
  const raw = await native?.getAudioRoutes();
  return raw ? (JSON.parse(raw) as AudioRoute[]) : [];
}

/**
 * What would stop this device from ringing. Worth showing behind a "calls not
 * working?" link rather than only reading in a bug report.
 */
export async function getDiagnostics(): Promise<Diagnostics | null> {
  const raw = await native?.getDiagnostics();
  return raw ? (JSON.parse(raw) as Diagnostics) : null;
}

/** Android 14+ settings page for the full-screen intent permission. */
export function openFullScreenIntentSettings(): void {
  native?.openFullScreenIntentSettings();
}

export function requestBatteryOptimisationExemption(): void {
  native?.requestBatteryOptimisationExemption();
}

/**
 * Deep links into the vendor autostart screen on the OEMs that have one.
 * Resolves `false` when there is nothing to open, so you can hide the button.
 */
export function openAutoStartSettings(): Promise<boolean> {
  return native ? native.openAutoStartSettings() : Promise.resolve(false);
}

const TelecomAndroid = {
  isSupported,
  configure,
  addEventListener,
  getCurrentCall,
  reportIncoming,
  startOutgoing,
  answer,
  reportConnected,
  reportEnded,
  setMuted,
  setHeld,
  setAudioRoute,
  getAudioRoutes,
  getDiagnostics,
  openFullScreenIntentSettings,
  requestBatteryOptimisationExemption,
  openAutoStartSettings,
};

export default TelecomAndroid;
