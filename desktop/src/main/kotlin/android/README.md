Minimal stand-ins for the few Android APIs that the shared (platform-free) app code touches, so the desktop build can
compile those files unchanged. Same idea as sherpa-onnx's kotlin-api-examples (`faked-asset-manager.kt`).
Rules: keep them tiny and behaviour-faithful; never add UI, Bitmap or GL here (those get real desktop code).
