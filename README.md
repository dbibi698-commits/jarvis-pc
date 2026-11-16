# JarvisPC — Windows app for JARVIS

Java Swing desktop app for Windows. Pairs with the Jarvis Android app by scanning a QR code, then controls the phone from the PC and vice versa — text/voice commands, live voice call, live phone screen, file transfer, clipboard sync, Find my phone, PC shutdown/restart/sleep.

## Install (Windows)
Download `JarvisPC-Setup.exe` from the latest Actions run (Artifacts > exe-installer), double-click, install, then run **JarvisPC** from the Start Menu. No Java install needed — the runtime is bundled.

## Build the EXE (GitHub Actions)
Push to `main` (or Run workflow) — the `build-exe` workflow builds `JarvisPC-Setup.exe` with jpackage + WiX and uploads it as the `exe-installer` artifact.

## Run from source
`javac -encoding UTF-8 -cp libs/zxing-core-3.5.3.jar:libs/json-20240303.jar -d classes src/JarvisPC.java` then `java -cp classes:libs/zxing-core-3.5.3.jar:libs/json-20240303.jar JarvisPC` (or use the packaged jar).
