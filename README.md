# Support Agent Standalone Android MVP

Runs entirely on an Android phone. No laptop, backend, Wi-Fi server, or cloud is required.

## Open/build
Open the `android` folder in Android Studio when you have access to a computer. Build and install the APK on your Pixel 10.

## Demo flows
Try:
- Hi
- My order is late
- My order ORD-1001 is late
- I want to cancel ORD-1002
- My order ORD-1001 has a missing item
- The refund failed
- I want to talk to a human

The app contains a local mock business API and local persistence. The "AI" is a deterministic rule-based support agent so it works offline without an API key.

## Demo orders
ORD-1001: OUT_FOR_DELIVERY, Paneer Biryani, ₹320
ORD-1002: PREPARING, Burger Combo, ₹280
ORD-1003: DELIVERED, Pizza, ₹450 (refund failure simulation)

## Cloud build
GitHub Actions builds a debug APK automatically whenever changes are pushed to `main`. The APK is uploaded as the `support-agent-debug-apk` workflow artifact.
