# SysMLInteractiveModelBenchmark results

## Scope

`SysMLInteractiveModelBenchmark`
(`org.omg.sysml.interactive/src/org/omg/sysml/interactive/profiler/SysMLInteractiveModelBenchmark.java`)
is a fresh-JVM measurement of loading, resolving and transforming the standard
library plus a recursively discovered SysML corpus, with the implicit-specialization
service configured to cache every type — the same policy
`org.omg.kerml.xtext.PilotImplicitSpecializationService` installs in production. It
exists to make the performance impact of changes to the implicit-specialization
service/cache layer (see the
[mechanism reference](../../org.omg.sysml.logic/doc/implicit-specialization.md))
visible over time, not to certify absolute numbers: a single machine's wall-clock
timings vary with load, so read trends across runs rather than isolated values.

## Protocol

```sh
mvn clean verify
java -Xms2g -Xmx4g \
  -cp org.omg.sysml.interactive/target/org.omg.sysml.interactive-<version>-all.jar \
  org.omg.sysml.interactive.profiler.SysMLInteractiveModelBenchmark \
  <standard-library-folder> <models-folder>
```

For this repository, the fixed parameters are:

- Standard library folder: `sysml.library`
- Models folder: `sysml/src`


Each run is a single fresh JVM without an in-JVM warm-up. Comparative series
retain each valid run and state the execution order. The benchmark measures four
phases in order — `load_libraries`, `load_models`, `resolution`, `transformation` —
each reported as `BENCHMARK,<phase>,<wall_seconds>,<gc_count>,<gc_milliseconds>`.
After the `loaded`, `resolved` and `transformed` checkpoints it also reports
`CACHES,<phase>,types=<N>,cached=<N>,complete=<N>,uncached=<N>`: how many `Type`s
exist, how many have a specialization cache attached, how many of those caches are
both raw- and reduced-complete, and how many types have no cache yet.

Note: Obviously, the measurements are computer-dependent.

## Measured results

Both versions were measured on the same machine (Linux WSL2, Temurin 21+35,
`-Xms2g -Xmx4g`), with the fixed parameters above (255 `.sysml` files, 349 resources once
library dependencies are counted), three runs each, every run in a fresh JVM. The baseline
was measured on 2026-10-01 and the current implementation on 2026-10-02, in separate
sessions: the delta below compares measurements taken on different days.

### Baseline (code before the implicit-specialization extraction)

Implicit specializations computed by the `ElementAdapter` hierarchy, as before the
refactoring. This version of the benchmark has no `CACHES` checkpoints. Built with
`mvn clean package -DskipTests`; its tests were not run for this measurement.

| Run  | load_libraries | load_models |                resolution |        transformation |
| ---- | -------------: | ----------: | ------------------------: | --------------------: |
| 1    |         1.41 s |      0.82 s | 297.33 s (320 GC, 809 ms) | 6.63 s (7 GC, 68 ms) |
| 2    |         1.33 s |      0.86 s | 299.82 s (321 GC, 876 ms) | 6.39 s (6 GC, 60 ms) |
| 3    |         1.36 s |      0.86 s | 292.16 s (320 GC, 855 ms) | 6.07 s (7 GC, 64 ms) |
| Mean |         1.37 s |      0.85 s |              **296.44 s** |              **6.36 s** |

Resource diagnostics in every run: 0 errors after `load_models`, 5 after `resolution`, 4
after `transformation`.

### Current implementation

Implicit specializations computed by the rule-based `ImplicitSpecializationService`, with
the Pilot cache policy (every Type reaching the model is cached).
Measured after a full `mvn clean verify` (`BUILD SUCCESS`, 1,649 tests, 0
failures, 0 errors, 22 skipped).

| Run  | load_libraries | load_models |                resolution |        transformation |
| ---- | -------------: | ----------: | ------------------------: | --------------------: |
| 1    |         1.34 s |      0.78 s | 148.85 s (149 GC, 434 ms) | 6.41 s (6 GC, 55 ms) |
| 2    |         1.32 s |      0.78 s | 144.11 s (150 GC, 411 ms) | 6.43 s (7 GC, 65 ms) |
| 3    |         1.33 s |      0.82 s | 144.21 s (150 GC, 460 ms) | 6.29 s (6 GC, 57 ms) |
| Mean |         1.33 s |      0.79 s |              **145.73 s** |              **6.38 s** |

| Checkpoint  |  Types |          Cached |        Complete | Uncached |
| ----------- | -----: | --------------: | --------------: | -------: |
| loaded      | 55,279 |               0 |               0 |   55,279 |
| resolved    | 60,442 |  28,394 (47.0%) |    2,786 (4.6%) |   32,048 |
| transformed | 70,349 | 70,322 (99.96%) | 70,077 (99.61%) |       27 |

Identical in the three runs. Resource diagnostics in every run: 0 errors after
`load_models`, 0 after `resolution`, 0 after `transformation`.

**Delta against the baseline:** resolution −150.71 s (−50.8%) on average, each current run
being faster than every baseline run; transformation +0.02 s (+0.2%); loading phases
unchanged. The 5 / 4 linking errors of the baseline disappear (see "Linking and provisional
name-resolution failures" in the
[mechanism reference](../../org.omg.sysml.logic/doc/implicit-specialization.md)).


## ST6RI-982 additional-members API removal — 2026-10-23

Reference: `a43754615960b1ae1d40964ad7cccf62b85ffd99`.
Change: working tree on `obeo/ST6RI-982`, removing the additional-members API
and its Java/Xtend callers. The reference artifacts match its committed bundled
JARs. The changed artifacts follow a successful root `mvn clean verify`
(1697 tests, no failures or errors, 22 skipped).

### Complete-corpus benchmark

Java 21+35-LTS, `-Xms2g -Xmx4g`, 255 model files, 349 completed resources.
Three fresh JVMs per version, executed sequentially in this order:
reference 1, change 1, change 2, reference 2, change 3, reference 3.
No concurrent build or benchmark ran during these measurements.

| Version / run | load_libraries | load_models | resolution | transformation |
|---|---:|---:|---:|---:|
| Reference 1 | 1.486 s | 0.941 s | 149.779 s | 6.820 s |
| Change 1 | 1.516 s | 0.945 s | 160.381 s | 6.945 s |
| Change 2 | 1.705 s | 1.016 s | 162.867 s | 6.934 s |
| Reference 2 | 1.640 s | 0.977 s | 165.475 s | 7.487 s |
| Change 3 | 1.634 s | 1.027 s | 166.373 s | 7.116 s |
| Reference 3 | 1.610 s | 0.994 s | 168.021 s | 7.121 s |

| Phase | Reference mean | Change mean | Delta |
|---|---:|---:|---:|
| load_libraries | 1.579 s | 1.619 s | +2.5% |
| load_models | 0.971 s | 0.996 s | +2.6% |
| resolution | 161.092 s | 163.207 s | +1.3% |
| transformation | 7.143 s | 6.999 s | -2.0% |

The mean resolution delta against the reference is +1.3%; the median changes
from 165.475 s to 162.867 s (−1.6%). Reference resolution ranges from
149.779 to 168.021 s, versus 160.381 to 166.373 s for the change.
Transformation is 2.0% faster on average. The initially observed +7.1% resolution
delta does not repeat consistently across the series; these measurements do not
establish a reproducible slowdown or prove exact performance equivalence.

Every run reports zero resource diagnostics at all three checkpoints.
Each checkpoint contains 70,387 types. Resolved cache counts are 29,720 for the
reference and 29,717 for the change, with 2,786 complete in both versions.
After transformation both versions have 70,360 cached, 70,115 complete and
27 uncached types.

`ImplicitSpecializationDump` produces byte-for-byte identical files for the two
versions: 70,387 types × two views × two checkpoints = 281,548 lines, with
identical ordering of raw and reduced specializations.

### Interactive parsing profiler

`SysMLInteractiveParsingProfiler` processes and validates these three files,
in the same order within each fresh JVM. Its measurements include formatting and
printing the result. The library path is absolute.

```sh
java -Xms2g -Xmx4g \
  -cp org.omg.sysml.interactive/target/org.omg.sysml.interactive-<version>-all.jar \
  org.omg.sysml.interactive.profiler.SysMLInteractiveParsingProfiler \
  "$PWD/sysml.library" \
  "sysml/src/training/23. State Definitions/State Definition Example-1.sysml" \
  "sysml/src/training/23. State Definitions/State Definition Example-2.sysml" \
  "sysml/src/training/30. Calculations/Calculation Definitions.sysml"
```

Three fresh JVMs per version, in the same reference/change sequence as above.
Times below are milliseconds.

| Version / run | Libraries and initialization | Explicit transitions | Shorthand transitions | Calculations | Three-example total |
|---|---:|---:|---:|---:|---:|
| Reference 1 | 2947 | 418 | 84 | 144 | 646 |
| Change 1 | 2969 | 420 | 89 | 147 | 656 |
| Change 2 | 3087 | 462 | 90 | 158 | 710 |
| Reference 2 | 3093 | 459 | 92 | 164 | 715 |
| Change 3 | 3135 | 475 | 92 | 167 | 734 |
| Reference 3 | 3130 | 464 | 90 | 165 | 719 |
| Reference mean | 3056.7 | 447.0 | 88.7 | 157.7 | 693.3 |
| Change mean | 3063.7 | 452.3 | 90.3 | 157.3 | 700.0 |

The three-example mean changes from 693.3 to 700.0 ms (+1.0%); library loading
and initialization change from 3056.7 to 3063.7 ms (+0.2%).
The example totals range from 646 to 719 ms for the reference and 656 to 734 ms
for the change. The median changes from 715 to 710 ms (−0.7%).
No processing errors were reported, and all six outputs are identical after
normalizing generated UUIDs and timing lines. This small series shows no
consistent slowdown; it does not prove performance equivalence for other inputs.
