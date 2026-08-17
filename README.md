# AutoFlow

Android utility that sends one explicit MetaTrader 5 (`net.metaquotes.metatrader5`) market-order button press through an explicitly enabled Accessibility Service.

It has no market analysis, price prediction, indicator, network service, clipboard access, overlay, or stored trading credential. Start opens MT5; each + requests one BUY and each − requests one SELL.

## Safe operation

1. Install MetaTrader 5 and enable **MT5 lot automation** in Android Accessibility settings.
2. Tap **Start**, then open the XAUUSD market-order screen in MT5.
3. Use + for one BUY or − for one SELL.

The service accepts only a visibly confirmed Demo/Trial account, one visible XAUUSD-family symbol, one unambiguous lot field, confirmed market-execution mode, and one unambiguous BUY or SELL button. It sets and verifies 0.01 before the button press. Requests are processed one at a time; **Stop** cancels queued requests.

## Build

Use JDK 17+ and Android SDK Platform 36:

```powershell
.\gradlew.bat clean build test lint
```

Release builds are code/resource-shrunk. Signing material is supplied only through external process environment variables and is never committed.
