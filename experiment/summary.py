"""Summarize measured stage latency; input contains no text or audio."""
import argparse
import json
import math
from pathlib import Path

STAGES = ('stt_ms', 'translation_ms', 'tts_start_ms', 'total_ms')

def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered)*fraction)-1)] if ordered else None

def summarize(rows):
    measurements = {key: [] for key in STAGES}
    failures = 0
    for row in rows:
        if row.get('success') is not True:
            failures += 1
            continue
        for key in STAGES:
            value = row.get(key)
            if value is None:
                continue
            if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or value < 0:
                raise ValueError('Invalid latency')
            measurements[key].append(value)
    return {'attempts': len(rows), 'failures': failures,
            'latency': {key: {'count': len(values), 'p50': percentile(values, .5), 'p95': percentile(values, .95)}
                        for key, values in measurements.items()}}

def main():
    p = argparse.ArgumentParser(); p.add_argument('jsonl', type=Path); args = p.parse_args()
    with args.jsonl.open() as source:
        rows = [json.loads(line) for line in source if line.strip()]
    print(json.dumps(summarize(rows), allow_nan=False, indent=2))

if __name__ == '__main__': main()
