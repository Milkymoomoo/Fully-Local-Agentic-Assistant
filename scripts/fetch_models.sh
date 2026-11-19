#!/usr/bin/env bash
# Downloads the small speech models that are bundled inside the APK
# Only the wake-word model (~6 MB) is bundled. Run once before building.
set -euo pipefail
cd "$(dirname "$0")/.."
A=app/src/main/assets/models
B=https://github.com/k2-fsa/sherpa-onnx/releases/download
TMP=${TMPDIR:-/tmp}/belle_models; mkdir -p "$TMP" "$A/voices"
py() { python3 - "$@"; }
untar() { python3 -c "import tarfile,sys; tarfile.open(sys.argv[1]).extractall(sys.argv[2], filter='data')" "$1" "$2"; }

if [ ! -f "$A/kws/encoder.int8.onnx" ]; then
  curl -fsSL -o "$TMP/kws.tar.bz2" $B/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
  untar "$TMP/kws.tar.bz2" "$TMP"
  K=$TMP/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
  mkdir -p $A/kws
  cp $K/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx $A/kws/encoder.int8.onnx
  cp $K/decoder-epoch-12-avg-2-chunk-16-left-64.onnx $A/kws/decoder.onnx
  cp $K/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx $A/kws/joiner.int8.onnx
  cp $K/tokens.txt $A/kws/tokens.txt
  cp app/src/test/resources/bpe.vocab $A/kws/bpe.vocab   # generated from bpe.model with sentencepiece
fi
# Background-removal (matting) model for the 2D cutout: U2-Net-P, Apache-2.0, 4.6 MB
U2=https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx
if [ ! -f "$A/u2netp.onnx" ]; then
  curl -fsSL -o "$A/u2netp.onnx" $U2
  echo "309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8  $A/u2netp.onnx" | sha256sum -c -
fi
# Only the wake-word model is bundled now; speech recognition and voices are imported on first run
# (Belle Downloader app or file import). The rest of this script is kept for test fixtures.
exit 0
if [ ! -f "$A/silero_vad.onnx" ]; then curl -fsSL -o $A/silero_vad.onnx $B/asr-models/silero_vad.onnx; fi
if [ ! -f "$A/stt-moonshine-tiny-en/encoder_model.ort" ]; then
  curl -fsSL -o "$TMP/stt.tar.bz2" $B/asr-models/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2
  untar "$TMP/stt.tar.bz2" "$TMP"
  mkdir -p $A/stt-moonshine-tiny-en
  cp $TMP/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/{encoder_model.ort,decoder_model_merged.ort,tokens.txt,LICENSE} $A/stt-moonshine-tiny-en/
fi
V=vits-piper-en_US-amy-medium-int8
if [ ! -d "$A/voices/$V" ]; then
  curl -fsSL -o "$TMP/v.tar.bz2" $B/tts-models/$V.tar.bz2
  untar "$TMP/v.tar.bz2" "$A/voices"
fi
du -sh $A/*
