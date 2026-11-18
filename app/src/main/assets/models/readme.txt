Place pretrained TFLite models here:
- yamnet.tflite (Apache 2.0, ~3.7MB, audio genre/sound classifier)
- genre_model.tflite
- language_model.tflite
- mood_model.tflite

FPS Booster (RIFE) model
- rife_v426.onnx : RIFE v4.26 (Practical-RIFE family), 22,748,049 bytes
  sha256 af25762dfec02a4bbb949decea63988b01fa56c46c0ff9dc66ac8e2f12cbb661
  Contract: input  [1, 7, H, W] float32 NCHW = img0 RGB (0..1), img1 RGB (0..1), timestep plane (0..1)
            output [1, 3, H, W] float32 RGB (0..1); H and W multiples of 32.
  Licence: MIT per the Practical-RIFE README (author statement; weights were distributed via Google Drive/Baidu
  without a separate licence file, training-data terms not reviewed). Keep the notice in ATTRIBUTION.md.
  Note: the project brief names Practical-RIFE 4.25 Lite. This bundled file is 4.26, not 4.25 Lite; to switch, replace
  the file, update this hash and re-run tools/rife_check.py and the on-device benchmark.
