import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

/**
 * The codegen spec.
 *
 * Everything here is deliberately flat and primitive: codegen has no union
 * types and no optional object fields worth trusting, and the whole point of
 * this module is that it works when nothing else is running. A struct that
 * fails to marshal at 3am on a cold start is not a trade worth making.
 *
 * `configureJson`, `reportIncomingJson` and friends take JSON strings for the
 * same reason — one shape, one parser, no codegen surprises between RN versions.
 */
export interface Spec extends TurboModule {
  /** Channels, ringtone, timeouts, the optional decline webhook. JSON. */
  configure(optionsJson: string): void;

  /** The call this process is currently in the middle of, as JSON, or null. */
  getCurrentCall(): Promise<string | null>;

  /** A push received somewhere other than our own FCM service. JSON. */
  reportIncoming(payloadJson: string): Promise<void>;

  /** Outgoing call, so Telecom and the system call log know about it. JSON. */
  startOutgoing(payloadJson: string): Promise<void>;

  /**
   * The app answered it — from its own ring screen, not the notification.
   * No-op unless the call is still ringing, so it is safe to call on every
   * accept path without knowing which one got there first.
   */
  answer(callId: string): void;

  /**
   * Media is up. Cancels the answer deadman — without this the call is torn
   * down, because an answered call that never connects is worse than a
   * missed one.
   */
  reportConnected(callId: string): void;

  /** We ended it. `reason` is one of the CallEndReason values. */
  reportEnded(callId: string, reason: string): void;

  setMuted(callId: string, muted: boolean): void;
  setHeld(callId: string, held: boolean): void;

  /** 'earpiece' | 'speaker' | 'bluetooth' | 'wired' */
  setAudioRoute(callId: string, route: string): void;
  getAudioRoutes(): Promise<string>;

  /** Diagnostics: what would stop this device from ringing. JSON. */
  getDiagnostics(): Promise<string>;

  /** Android 14+: the full-screen intent settings page. */
  openFullScreenIntentSettings(): void;
  /** The battery-optimisation exemption dialog. */
  requestBatteryOptimisationExemption(): void;
  /** Best-effort deep link into the OEM autostart screen. Returns false if none. */
  openAutoStartSettings(): Promise<boolean>;

  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RNTelecomAndroid');
