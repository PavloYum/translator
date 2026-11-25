import io
import struct
import unittest
import wave
from experiment.audio import inspect_wav, MAX_BYTES


def wav(sample=0, rate=16000, channels=1):
    buffer = io.BytesIO()
    with wave.open(buffer, 'wb') as out:
        out.setparams((channels, 2, rate, 0, 'NONE', 'not compressed'))
        out.writeframes(struct.pack('<h', sample) * 16000 * channels)
    return buffer.getvalue()

class AudioTests(unittest.TestCase):
    def test_silence(self):
        result = inspect_wav(wav())
        self.assertTrue(result.silent)
        self.assertIsNone(result.rms_dbfs)
        self.assertEqual(result.seconds, 1)

    def test_clipping(self):
        result = inspect_wav(wav(-32768))
        self.assertEqual(result.clipped_fraction, 1)
        self.assertEqual(result.peak, 32768)
        self.assertFalse(result.silent)

    def test_format(self):
        for data in (wav(rate=44100), wav(channels=2), b'broken', wav()[:-30], b'x'*(MAX_BYTES+1)):
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                inspect_wav(data)

if __name__ == '__main__':
    unittest.main()
