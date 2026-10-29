import numpy as np
import onnxruntime as ort

def main():
    model_path = 'app/src/main/assets/models/rife_v426.onnx'
    session = ort.InferenceSession(model_path)

    print("Inputs:")
    for inp in session.get_inputs():
        print(f"  name: {inp.name}, shape: {inp.shape}, type: {inp.type}")

    print("Outputs:")
    for out in session.get_outputs():
        print(f"  name: {out.name}, shape: {out.shape}, type: {out.type}")

    H, W = 64, 64
    # Create smooth gradients for img0 and img1
    x = np.linspace(0, 1, W, dtype=np.float32)
    y = np.linspace(0, 1, H, dtype=np.float32)
    xx, yy = np.meshgrid(x, y)

    img0 = np.stack([xx, yy, np.zeros_like(xx)], axis=0)[None, ...] # (1, 3, H, W)
    img1 = np.stack([xx, np.zeros_like(yy), yy], axis=0)[None, ...] # (1, 3, H, W)

    def test_packed(i0, i1, t_val):
        t_plane = np.full((1, 1, H, W), t_val, dtype=np.float32)
        packed = np.concatenate([i0, i1, t_plane], axis=1) # (1, 7, H, W)
        res = session.run([session.get_outputs()[0].name], {session.get_inputs()[0].name: packed})[0]
        return res

    def psnr(a, b):
        mse = np.mean((a - b) ** 2)
        if mse == 0:
            return float('inf')
        return 20 * np.log10(1.0 / np.sqrt(mse))

    # Test [img0, img1, t]
    out_t0 = test_packed(img0, img1, 0.0)
    out_t1 = test_packed(img0, img1, 1.0)
    out_t05 = test_packed(img0, img1, 0.5)

    print("\n--- Testing [img0, img1, timestep] ---")
    print(f"PSNR(img0, output at t=0): {psnr(img0, out_t0):.2f} dB")
    print(f"PSNR(img1, output at t=1): {psnr(img1, out_t1):.2f} dB")
    print(f"MSE(img0, out_t05): {np.mean((img0 - out_t05)**2):.4f}")
    print(f"MSE(img1, out_t05): {np.mean((img1 - out_t05)**2):.4f}")

    # Test [img1, img0, t]
    out_rev_t0 = test_packed(img1, img0, 0.0)
    out_rev_t1 = test_packed(img1, img0, 1.0)

    print("\n--- Testing [img1, img0, timestep] ---")
    print(f"PSNR(img1, output at t=0): {psnr(img1, out_rev_t0):.2f} dB")
    print(f"PSNR(img0, output at t=1): {psnr(img0, out_rev_t1):.2f} dB")

if __name__ == "__main__":
    main()
