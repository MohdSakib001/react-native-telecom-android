# Entry points the system instantiates by name — the manifest is the only
# reference to them, so R8 cannot see they are used.
-keep class com.telecomandroid.CallService { *; }
-keep class com.telecomandroid.CallActionReceiver { *; }
-keep class com.telecomandroid.IncomingCallActivity { *; }
-keep class com.telecomandroid.TelecomFirebaseMessagingService { *; }
