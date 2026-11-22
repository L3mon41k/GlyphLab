# GlyphLab

A lightweight editor for the 25×25 Glyph Matrix on Nothing Phone (3).

## Current version

**0.2.0 — Circular editor and Glyph Matrix output**

- Circular 25×25 Glyph Matrix preview with square LEDs arranged inside the round boundary
- Tap a pixel to toggle it on or off
- Drag to draw
- Clear the canvas
- Connect to Nothing Glyph Matrix through the official Glyph Matrix SDK
- Send the current drawing to the phone's physical Glyph Matrix
- Turn off the app's Glyph Matrix output
- Connection and output status in the app

The app uses the official setAppMatrixFrame() API so it does not directly replace the system's general Glyph Toy frame. The matrix output is one-way: the SDK exposes sending and closing the app display, but does not provide a public method to read back the physical LED state.

## Build

GitHub Actions downloads the official Nothing Glyph Matrix SDK during the build and creates a debug APK on every push to main, pull request, or manual workflow run.
