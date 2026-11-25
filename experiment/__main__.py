import argparse
import json
from pathlib import Path
from .audio import inspect_wav, MAX_BYTES


def main():
    parser = argparse.ArgumentParser(description="Validate phone recordings locally; never uploads audio")
    parser.add_argument("wav", type=Path)
    args = parser.parse_args()
    try:
        with args.wav.open("rb") as stream:
            data = stream.read(MAX_BYTES + 1)
        print(json.dumps(inspect_wav(data).to_dict(), ensure_ascii=False, allow_nan=False))
    except (OSError, ValueError) as error:
        parser.exit(2, f"Audio rejected: {error}\n")

if __name__ == "__main__":
    main()
