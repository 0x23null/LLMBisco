import sys, json, os
import numpy as np
try:
    import tensorflow as tf
except Exception:
    tf = None

MODEL_PATH = os.path.join(os.path.dirname(__file__), 'sicbo_core.keras')

_model = None
if tf is not None:
    try:
        _model = tf.keras.models.load_model(MODEL_PATH)
    except Exception:
        _model = None

def _encode_history(hist: str):
    mapping = {'T': 1.0, 'X': 0.0}
    return np.array([[mapping.get(ch, 0.0) for ch in hist]], dtype=np.float32)

def main():
    raw = sys.stdin.read()
    try:
        data = json.loads(raw)
        hist = data.get('history', '')
        if _model is None:
            raise RuntimeError('model not loaded')
        x = _encode_history(hist)
        pred = _model.predict(x, verbose=0)[0]
        idx = int(np.argmax(pred))
        mapping = {0: 'TAI', 1: 'XIU', 2: 'SKIP'}
        result = {'pick': mapping.get(idx, 'SKIP')}
    except Exception:
        result = {'pick': 'SKIP'}
    sys.stdout.write(json.dumps(result))

if __name__ == '__main__':
    main()
