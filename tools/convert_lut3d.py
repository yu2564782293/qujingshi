"""Convert HuiZeng/Image-Adaptive-3DLUT pretrained weights (sRGB, paired) to mobile assets.

Usage:
    python convert_lut3d.py <classifier.pth> <LUTs.pth> <out_classifier.tflite> <out_luts.bin>

Outputs:
  - out_classifier.tflite: the weight-predictor CNN.
      input : 1x256x256x3 float32, RGB in 0..1 (resize on device before feeding)
      output: 1x3 float32 = blending weights (w0, w1, w2), NOT softmaxed
  - out_luts.bin: 3 basis LUTs as little-endian float32,
      layout [lut][c][b][g][r] with dim=33 (torch tensor (3,33,33,33) dumped as-is;
      within a channel the flat index is b*33^2 + g*33 + r).
      Final LUT = w0*LUT0 + w1*LUT1 + w2*LUT2, values clamp to 0..1.

Architecture mirrored from models_x.py Classifier (PyTorch Sequential):
  Upsample(256) -> [Conv(3,16,s2,p1) LReLU IN] -> [16,32] -> [32,64] -> [64,128]
  -> [Conv(128,128,s2,p1) LReLU] -> Dropout -> Conv(128,3,k8)
PyTorch pads 1 on BOTH sides; TF 'same' pads asymmetrically, so every conv is
built as ZeroPadding2D(1) + Conv2D(valid) to match exactly.
"""
import sys

import numpy as np
import tensorflow as tf
import torch


def build_keras():
    L = tf.keras.layers
    inp = tf.keras.Input((256, 256, 3))

    def block(x, out, norm, name):
        x = L.ZeroPadding2D(1)(x)
        x = L.Conv2D(out, 3, strides=2, padding="valid", name=f"{name}_conv")(x)
        x = L.LeakyReLU(0.2)(x)
        if norm:
            x = L.GroupNormalization(groups=-1, epsilon=1e-5, name=f"{name}_in")(x)
        return x

    x = block(inp, 16, True, "b0")
    x = block(x, 32, True, "b1")
    x = block(x, 64, True, "b2")
    x = block(x, 128, True, "b3")
    x = block(x, 128, False, "b4")
    x = L.Conv2D(3, 8, padding="valid", name="head")(x)
    x = L.Reshape((3,))(x)
    return tf.keras.Model(inp, x)


# keras layer name -> (torch conv index, torch instancenorm index or None)
MAPPING = {
    "b0": (1, 3),
    "b1": (4, 6),
    "b2": (7, 9),
    "b3": (10, 12),
    "b4": (13, None),
    "head": (16, None),
}


def copy_weights(model, sd):
    for name, (ci, ni) in MAPPING.items():
        conv = model.get_layer(f"{name}_conv") if name != "head" else model.get_layer("head")
        w = sd[f"model.{ci}.weight"].numpy().transpose(2, 3, 1, 0)  # OIHW -> HWIO
        b = sd[f"model.{ci}.bias"].numpy()
        conv.set_weights([w, b])
        if ni is not None:
            gn = model.get_layer(f"{name}_in")
            gamma = sd[f"model.{ni}.weight"].numpy()
            beta = sd[f"model.{ni}.bias"].numpy()
            gn.set_weights([gamma, beta])


def torch_reference(sd, x_nchw):
    """Rebuild the torch classifier and run it, as ground truth."""
    import torch.nn as nn

    def dblock(i, o, norm):
        layers = [nn.Conv2d(i, o, 3, stride=2, padding=1), nn.LeakyReLU(0.2)]
        if norm:
            layers.append(nn.InstanceNorm2d(o, affine=True))
        return layers

    model = nn.Sequential(
        nn.Upsample(size=(256, 256), mode="bilinear"),
        nn.Conv2d(3, 16, 3, stride=2, padding=1),
        nn.LeakyReLU(0.2),
        nn.InstanceNorm2d(16, affine=True),
        *dblock(16, 32, True),
        *dblock(32, 64, True),
        *dblock(64, 128, True),
        *dblock(128, 128, False),
        nn.Dropout(p=0.5),
        nn.Conv2d(128, 3, 8, padding=0),
    )
    # keys in sd are "model.N.*"; strip the prefix for the bare Sequential
    stripped = {k[len("model."):]: v for k, v in sd.items()}
    model.load_state_dict(stripped)
    model.eval()
    with torch.no_grad():
        return model(torch.from_numpy(x_nchw)).squeeze().numpy()


def extract_luts(luts_path):
    raw = torch.load(luts_path, map_location="cpu")
    luts = []
    for key in ["0", "1", "2"]:
        entry = raw[key]
        tensor = entry["LUT"] if isinstance(entry, dict) else entry
        arr = tensor.detach().numpy().astype(np.float32)
        assert arr.shape == (3, 33, 33, 33), f"unexpected LUT shape {arr.shape}"
        luts.append(arr)
        print(f"LUT{key}: min {arr.min():.3f} max {arr.max():.3f}")
    return luts


def main():
    classifier_path, luts_path, out_tflite, out_bin = sys.argv[1:5]

    sd = torch.load(classifier_path, map_location="cpu")
    print("classifier keys:", sorted(sd.keys())[:4], "...")
    model = build_keras()
    copy_weights(model, sd)

    # verify against the torch reference on a fixed random input
    rng = np.random.default_rng(0)
    x = rng.random((1, 256, 256, 3), dtype=np.float32)
    ref = torch_reference(sd, x.transpose(0, 3, 1, 2).copy())
    got = model.predict(x, verbose=0)[0]
    print(f"torch weights {ref}, keras weights {got}, max diff {np.abs(ref - got).max():.6f}")
    assert np.abs(ref - got).max() < 1e-3, "keras port does not match torch reference"

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite = converter.convert()
    with open(out_tflite, "wb") as f:
        f.write(tflite)
    print(f"wrote {out_tflite} ({len(tflite) / 1e6:.2f} MB)")

    # tflite sanity vs keras
    interp = tf.lite.Interpreter(model_content=tflite)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    interp.set_tensor(inp["index"], x)
    interp.invoke()
    tfl = interp.get_tensor(out["index"])[0]
    print(f"tflite weights {tfl}, diff vs keras {np.abs(tfl - got).max():.6f}")

    luts = extract_luts(luts_path)
    with open(out_bin, "wb") as f:
        for arr in luts:
            f.write(np.ascontiguousarray(arr).tobytes())
    print(f"wrote {out_bin} ({3 * arr.nbytes / 1e6:.2f} MB)")


if __name__ == "__main__":
    main()
