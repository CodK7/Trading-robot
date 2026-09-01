# AutoFlow

Android utility for explicit XAUUSD market-order requests in MetaTrader 5 (`net.metaquotes.metatrader5`) through an explicitly enabled Accessibility Service.

It has no market analysis, price prediction, signal generation, network service, clipboard access, overlay, or stored trading credential. The configurable adjustment amount moves a local yellow offset: each accepted + raises it and requests one BUY; each accepted − lowers it and requests one SELL. The offset is a manual command reference, not a live market price.

## Safe operation

1. Install MetaTrader 5, sign in to the intended Exness account, and enable **MT5 gold automation** in Android Accessibility settings.
2. Enter the yellow-line adjustment amount and tap **Start**.
3. Prepare the XAUUSD market-order screen in MT5 once.
4. Use +/− in AutoFlow or the ongoing notification while MT5 is visible.

The service requires one visible XAUUSD-family symbol, one unambiguous lot field, confirmed market-order mode, and one unambiguous BUY or SELL button. It supports demo and real accounts, sets and verifies 0.01 before the button press, and records whether an Exness label was visible. Requests are processed one at a time; **Stop** atomically invalidates work that has not committed the MT5 button press.

## Build

Use JDK 17+ and Android SDK Platform 36:

```powershell
.\gradlew.bat clean build test lint
```

Release builds are code/resource-shrunk. Signing material is supplied only through external process environment variables and is never committed.

`AutoFlow-debug.apk` is the current installable test artifact. Production release output must be signed with the project's private release key; the preserved `AutoFlow-legacy-1.0.apk` is the previous implementation and must not be distributed as version 1.1.
