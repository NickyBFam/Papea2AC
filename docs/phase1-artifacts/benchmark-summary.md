# Phase 1 benchmark continuation

JMH 1.37, 3 forks, 5 x 1 s warmup, 10 x 1 s measurement, one thread, fixed 512 MiB heap, GC profiler. Windows 11 x64 / i7-13700KF / Temurin 25.0.4.1+1. Shared interactive host, uncontrolled background work; Paper smoke overlapped the beginning of the run. Descriptive results, not performance acceptance. These five foundation paths do not benchmark complete native reflective normalization or live file output.

| Path | ns/op | Error | B/op |
|---|---:|---:|---:|
| correlate | 967.367 | 126.749 | 1968.007 |
| enqueueDequeue | 35.469 | 1.898 | 24.000 |
| journal | 33.145 | 1.208 | 168.000 |
| normalize | 11.014 | 1.065 | 88.000 |
| sequence | 48.459 | 4.242 | 72.000 |

Raw fork samples and JMH confidence metadata are in `benchmarks.json`. The two subsequent changes only tighten semantic-record accounting and writer admission, outside these benchmark paths.
