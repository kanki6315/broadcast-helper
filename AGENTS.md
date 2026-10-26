# Agent instructions


## FlightDeck managed tests

For fast development checks, run `bash .flightdeck/test-fast.sh` from the repository root. This runs the host-only suite directly, without FlightDeck, simulator leases, signing, or release gates. It exits nonzero on failure and prints its artifact directory. Ordinary editing and builds that do not use simulators also need no lease. These checks do not replace required iOS/UI tests.

For the full suite including simulator tests, run `flightdeck start pitpass --worktree "$PWD" --mode test --wait-seconds 120` (use the absolute worktree root). This returns a job ID immediately. Poll `flightdeck detail JOB_ID` until terminal status; only `succeeded` means the tests passed. Use `flightdeck cancel JOB_ID` to stop a running or waiting job. Tests acquire, heartbeat, and release their simulators automatically after child processes stop; do not manually acquire or release leases for this command.

Busy devices cause a bounded wait with owners shown in job detail. On timeout the job fails without holding devices: continue host tests, or inspect `flightdeck devices` and retry with a free compatible `--device UUID` (repeat for Forkware phone/tablet) or a longer `--wait-seconds` up to 3600. Never steal a lease based on an old heartbeat. For an abandoned job, use `flightdeck recover JOB_ID` only after confirming its recorded processes stopped. Do not repeatedly retry an occupied device or treat a launch/timeout as passing tests.

Test jobs accept uncommitted changes and never archive, upload, or tag. Keep the worktree unchanged until its tests finish. Separate worktrees can test concurrently on different simulators, including while another worktree releases an app. The same worktree remains exclusive; releases still exclude other releases. Logs and results live in the returned job folder. Existing UI-test authorization requirements still apply. Manual leases are only needed for separate install/launch/debug operations. Existing worktrees must contain both `.flightdeck/test.sh` and `.flightdeck/test-fast.sh` from this integration.
