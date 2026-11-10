# Metal Detector (Termux-inspired)

Android magnetometer-based metal detector. Detects ferromagnetic objects
(steel, iron, magnets). Does NOT detect aluminum/copper (passive sensor).

## Build
CI auto-builds debug APK on push. Download from Actions artifacts.

## Install
    adb install -r app-debug.apk

## Limits
- AK09918C: 100 Hz max, Android limits to 50 Hz (SENSOR_DELAY_GAME)
- Only ferromagnetics (Fe, Ni, Co, steel, magnets)
- Not aluminum, copper, gold, brass
