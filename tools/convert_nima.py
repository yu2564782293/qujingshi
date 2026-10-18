"""Convert idealo NIMA (MobileNet aesthetic) weights to a float16 TFLite model.

Usage:
    python convert_nima.py <weights_mobilenet_aesthetic_0.07.hdf5> <out.tflite>

Weights: https://github.com/idealo/image-quality-assessment
(MobileNet trained on the AVA aesthetic dataset; MIT-style Apache-2.0 licensed repo)

Model architecture must match idealo's Nima class exactly:
MobileNet(include_top=False, pooling='avg') -> Dropout(0.75) -> Dense(10, softmax)
Input preprocessing at inference time: x / 127.5 - 1 (MobileNet convention).
Score = expected value over the 10-bin distribution: sum((i+1) * p_i).
"""
import sys

import numpy as np
import tensorflow as tf


def build_model(keras):
    base = keras.applications.MobileNet(
        input_shape=(224, 224, 3), include_top=False, pooling="avg", weights=None
    )
    x = keras.layers.Dropout(0.75)(base.output)
    out = keras.layers.Dense(10, activation="softmax")(x)
    return keras.Model(base.input, out)


def load(weights_path):
    # TF >= 2.16 ships Keras 3, whose legacy h5 loader usually handles these
    # weights; fall back to tf_keras (Keras 2) if the layout doesn't match.
    try:
        model = build_model(tf.keras)
        model.load_weights(weights_path)
        return model, "tf.keras"
    except Exception as e:  # noqa: BLE001
        print(f"tf.keras load failed ({e}); trying tf_keras", file=sys.stderr)
        import tf_keras

        model = build_model(tf_keras)
        model.load_weights(weights_path)
        return model, "tf_keras"


def main():
    weights_path, out_path = sys.argv[1], sys.argv[2]
    model, flavor = load(weights_path)
    print(f"weights loaded via {flavor}")

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite = converter.convert()
    with open(out_path, "wb") as f:
        f.write(tflite)
    print(f"wrote {out_path} ({len(tflite) / 1e6:.1f} MB)")

    # sanity check: output must be a 10-bin distribution summing to ~1
    interp = tf.lite.Interpreter(model_content=tflite)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    outd = interp.get_output_details()[0]
    rng = np.random.default_rng(0)
    x = (rng.random((1, 224, 224, 3), dtype=np.float32) * 2) - 1
    interp.set_tensor(inp["index"], x)
    interp.invoke()
    p = interp.get_tensor(outd["index"])[0]
    score = float(sum((i + 1) * v for i, v in enumerate(p)))
    print(f"sanity: output shape {p.shape}, sum {p.sum():.4f}, mean score {score:.2f}")
    assert p.shape == (10,) and abs(p.sum() - 1.0) < 1e-3


if __name__ == "__main__":
    main()
