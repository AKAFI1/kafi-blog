# Measurement machine

Keep this in sync with data/environment.yaml. The yaml is what gets rendered into
articles; this file is for the longer notes that do not belong in a disclosure block.

## Machine

- CPU:
- Cores / SMT:
- L1/L2/L3:
- RAM:
- OS / kernel:

## Quieting the machine

Record the exact commands used, not a description of them.

- Frequency scaling:
- Turbo / boost:
- C-states:
- Core isolation:
- Pinning:
- Anything else running during the run:

## Procedure

- JDK build string (full `java -version` output):
- JVM flags:
- JMH configuration (forks, warmup iterations, measurement iterations, mode):
- Number of independent runs, and whether results were consistent across them:
