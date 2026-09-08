# Evidence destroyed during 0.9.0, and what is left

Two records were deleted by me while trying to get a clean run. Neither can be recovered by any later
run. They are listed here rather than only in a review thread, because a project that loses evidence
and does not say so is worse than one that never had it.

## Series A harness failures: PRIMARY_LOG_LOST

Before starting a second harness roster I ran `rm -rf` on the previous series directory to get a
clean one. That directory held the only copies of two harness logs:

- a **STOCK** harness failure, status **UNEXPLAINED**. The contamination mechanism I proposed for it
  was refuted by the control I built to test it.
- a **refused** harness failure, status **UNATTRIBUTED**. Seen once while I was running Gradle
  concurrently on the same machine; it passed when re-run alone, which is not an attribution.

Surviving trace: my own quotations in the review thread. Those are secondary transcriptions, not
primary logs. No later series repairs this.

## The 31-failure unit run: PRIMARY_LOG_LOST and RAW_XML_LOST

Before re-running the unit suite I deleted `build/test-results`, which took the raw JUnit XML of a run
that had just reported 31 failures, and then I overwrote the transcript that summarised it.

What is left is secondary: the failures were temp-directory faults across three suites, disk had 141G
free, and no stale JUnit directories were present. **A subsequent run passed after a Gradle daemon
restart.** The daemon-retaining-a-vanished-`java.io.tmpdir` mechanism is a hypothesis consistent with
that and is **UNESTABLISHED**; the daemon and property state that could have settled it are gone.

## What now prevents a third

- `bench/harness-roster.sh` refuses to start when its output directory exists (exit 2). A new series
  gets a new directory; none is ever deleted to make room.
- `bench/unit-run.sh` runs into a fresh directory, refuses to reuse one, and copies the raw XML out
  before anything else can touch it. It fails closed (exit 3) when Gradle produced no XML or when the
  copy is incomplete, and reports no totals in that case, because a tidy zero reads like a clean run.

Neither guard recovers anything above.
