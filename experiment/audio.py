import io
import math
import struct
import wave
from dataclasses import asdict, dataclass

MAX_SECONDS = 60
MAX_BYTES = 16000 * 2 * MAX_SECONDS + 4096

@dataclass(frozen=True)
class AudioMetrics:
    seconds: float
    rms_dbfs: float | None
    peak: int
    clipped_fraction: float
    silent: bool

    def to_dict(self):
        return asdict(self)


def inspect_wav(data: bytes) -> AudioMetrics:
    """Accept complete mono PCM16/16k WAV, cap allocation before decoding."""
    if len(data) > MAX_BYTES:
        raise ValueError("Audio exceeds 60-second input limit")
    try:
        with wave.open(io.BytesIO(data), "rb") as source:
            if (source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getcomptype()) != (1, 2, 16000, "NONE"):
                raise ValueError("Expected mono PCM16 WAV at 16000 Hz")
            frames = source.getnframes()
            if not 1600 <= frames <= 16000 * MAX_SECONDS:
                raise ValueError("Duration must be between 0.1 and 60 seconds")
            pcm = source.readframes(frames)
            if len(pcm) != frames * 2:
                raise ValueError("Truncated WAV")
    except (wave.Error, EOFError) as error:
        raise ValueError("Invalid WAV") from error
    samples = struct.unpack(f"<{frames}h", pcm)
    rms = math.sqrt(sum(x*x for x in samples) / frames)
    return AudioMetrics(frames / 16000, 20 * math.log10(rms / 32768) if rms else None,
                        max(abs(x) for x in samples),
                        sum(abs(x) >= 32760 for x in samples) / frames,
                        rms < 32.768)
