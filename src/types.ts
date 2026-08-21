/**
 * The public vocabulary of the module.
 *
 * These types are duplicated, on purpose, by the Kotlin side — there is no
 * codegen struct anywhere in the bridge, so this file and `CallRecord.kt` are
 * the two ends of one JSON contract. Change one, change the other.
 */

export type CallType = 'audio' | 'video';

export type CallDirection = 'incoming' | 'outgoing';

export type AudioRoute = 'earpiece' | 'speaker' | 'bluetooth' | 'wired' | 'unknown';

/**
 * Why a call is over. `missed` and `timeout` are raised by native timers and can
 * therefore arrive when JS has never run, which is the whole reason they exist.
 */
export type CallEndReason =
  | 'local_hangup'
  | 'remote_hangup'
  | 'declined'
  | 'missed'
  | 'answered_elsewhere'
  | 'failed'
  | 'timeout';

/**
 * `ringing` → `answering` → `connected` → `ended`.
 *
 * `answering` is the gap between the user tapping answer and media actually
 * being up. It is a real state, not a formality: it is the window the answer
 * deadman watches, and the window in which a cold-started app discovers it has
 * a call to join.
 */
export type CallState = 'ringing' | 'answering' | 'connected' | 'ended';

export type IncomingCallPayload = {
  /** Your id for the call. Anything stable; it is echoed back in every event. */
  callId: string;
  callerName: string;
  callerId?: string;
  /** Shown in the CallStyle notification when it can be fetched in time. */
  avatarUrl?: string;
  type?: CallType;
  /** Opaque strings handed straight back to JS. Tokens, channel names, whatever. */
  extra?: Record<string, string>;
};

export type OutgoingCallPayload = {
  callId: string;
  peerName: string;
  peerId?: string;
  avatarUrl?: string;
  type?: CallType;
  extra?: Record<string, string>;
};

/**
 * Fired natively when a call is declined or missed.
 *
 * This exists because the common failure of every VoIP app is that declining a
 * call from the lock screen of a killed phone tells the server nothing — JS
 * never booted, so nothing was sent. Native does the request itself.
 *
 * `{callId}` and `{reason}` are substituted in `url` and `body`.
 */
export type DeclineWebhook = {
  url: string;
  method?: 'POST' | 'PUT' | 'PATCH';
  headers?: Record<string, string>;
  /** Defaults to `{"callId":"{callId}","reason":"{reason}"}`. */
  body?: string;
  timeoutMs?: number;
};

export type TelecomOptions = {
  /** Shown in the notification and registered with Telecom as the account label. */
  appName: string;

  /** Channel for the incoming ring. Recreated if the sound or importance change. */
  incomingChannelId?: string;
  incomingChannelName?: string;
  /** Channel for the low-importance ongoing-call notification. */
  ongoingChannelId?: string;
  ongoingChannelName?: string;

  /**
   * `content://` or `android.resource://` URI. `null` uses the system ringtone;
   * an empty string rings silently, which is what you want if your JS layer
   * plays its own sound.
   */
  ringtoneUri?: string | null;
  vibrate?: boolean;

  /** How long to ring before giving up and reporting `missed`. Default 45s. */
  ringTimeoutMs?: number;
  /**
   * How long after answering to wait for `reportConnected` before tearing the
   * call down as `failed`. Default 30s. An answered call that never connects is
   * worse than a missed one — it looks like your app broke.
   */
  answerTimeoutMs?: number;

  declineWebhook?: DeclineWebhook;

  logLevel?: 'silent' | 'warn' | 'debug';
};

export type CurrentCall = {
  callId: string;
  direction: CallDirection;
  state: CallState;
  type: CallType;
  /** `callerName` for incoming, `peerName` for outgoing. */
  displayName: string;
  peerId?: string;
  avatarUrl?: string;
  extra: Record<string, string>;
  /** Epoch ms. */
  startedAt: number;
  answeredAt: number | null;
  muted: boolean;
  held: boolean;
  route: AudioRoute;
};

export type TelecomEvent =
  /**
   * A push arrived and the phone is now ringing. Not fired on a cold start.
   *
   * `presentInApp` is true when your app was on screen as the call arrived, so
   * the notification stayed quiet and drew neither a heads-up nor a full-screen
   * intent — show your own ring UI. When false, the notification is the ring.
   */
  | { type: 'incoming'; call: CurrentCall; presentInApp: boolean }
  /**
   * The user arrived at the call: the full-screen intent fired, or they tapped
   * the notification. Show your ring UI now if you did not already.
   */
  | { type: 'presented'; callId: string }
  /** The user answered — from the notification, the lock screen, or a headset. */
  | { type: 'answer'; callId: string; call: CurrentCall }
  | { type: 'decline'; callId: string }
  | { type: 'ended'; callId: string; reason: CallEndReason }
  | { type: 'muteChanged'; callId: string; muted: boolean }
  | { type: 'holdChanged'; callId: string; held: boolean }
  | { type: 'audioRouteChanged'; callId: string; route: AudioRoute };

export type TelecomEventType = TelecomEvent['type'];

/**
 * What would stop this device from ringing, answered honestly.
 *
 * Every field being `true` is the only configuration in which a killed app
 * rings reliably. Anything else is a device that will silently miss calls, and
 * the user will blame you rather than their OEM.
 */
export type Diagnostics = {
  notificationsEnabled: boolean;
  incomingChannelEnabled: boolean;
  /** The channel's importance, if the user has turned it down from HIGH. */
  incomingChannelImportance: 'none' | 'min' | 'low' | 'default' | 'high' | 'unknown';
  /** Android 14+. Without it, the ring is a heads-up notification, not a screen. */
  canUseFullScreenIntent: boolean;
  ignoringBatteryOptimisations: boolean;
  /** Telecom accepted our self-managed phone account. Some OEMs never do. */
  telecomRegistered: boolean;
  /** `true` when a vendor autostart screen exists to send the user to. */
  hasAutoStartSettings: boolean;
  manufacturer: string;
  sdkInt: number;
};
