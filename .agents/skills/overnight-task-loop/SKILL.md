---
name: overnight-task-loop
description: Executes unchecked MVP tasks sequentially from docs/TASKS.md in autonomous mode with Gradle verification, a 3-attempt circuit breaker, per-task Git commits/pushes, decision logging, and final docs/HANDOFF_REPORT.md generation. Use when running unattended overnight development sessions.
---

# Overnight Task Loop Skill

Follow this exact multi-step workflow to execute `docs/TASKS.md` autonomously from top to bottom without human intervention.

## Pre-Flight Branch Check
1. Check the active Git branch (`git branch --show-current`).
2. If currently on `main` or `master`, create and switch to a feature branch (e.g., `git checkout -b feat/mvp-overnight`). Never commit directly to `main`/`master`.
3. Read `SPEC.md` and `docs/TASKS.md` to locate the first unchecked `- [ ]` task.

## Per-Task Execution Loop
Repeat for each unchecked item `- [ ]` in `docs/TASKS.md` in strict sequential order:

1. **Scope & Design:** Read the task requirements and relevant files under `.agents/rules/`. If a non-trivial technical choice is required, decide autonomously using the simplest idiomatic approach.
2. **Implement & Test:** Write real production code and comprehensive JVM unit tests (covering normal paths, edge cases, and malformed/error inputs). Never write mock playback engines or placeholder UI buttons.
3. **Verify:** Run the Gradle command(s) specified for the task (for full verification steps, follow `.agents/skills/android-audio-verify/SKILL.md`).
4. **Failure Handling (Circuit Breaker Decision Tree):**
   - **If verification passes (100% green):** Proceed to Step 5.
   - **If verification fails (Attempt 1 or 2):** Diagnose the root cause, fix the real issue (never weaken/skip tests), and re-run verification.
   - **If verification still fails after Attempt 3:**
     1. Stop retrying this item immediately.
     2. Reset staged and modified tracked files cleanly back to the last green commit:
        ```bash
        git reset --hard HEAD
        git clean -fd -e local.properties -e .idea -e BLOCKED.md -e DECISIONS.md -e docs/HANDOFF_REPORT.md
        ```
     3. Create or append to `BLOCKED.md` with the task ID, exact failing command, stderr/stack trace, and attempted fixes.
     4. Commit and push `BLOCKED.md` immediately (`git add BLOCKED.md && git commit -m "chore(blocked): record blocker for <task-id>" && git push -u origin <active-branch>`) so the diagnostic record is preserved before continuing or halting. (If `git push` fails due to a transient remote/network issue, keep the local commit and note the unpushed state in `docs/HANDOFF_REPORT.md`.)
     5. **Decision:**
        - If this is a **foundational blocker** (e.g., `T0.1` fails because Android SDK 37 or required Gradle JVM is missing and cannot be provisioned cleanly via `sdkmanager` without downgrading `compileSdk`): skip remaining tasks and jump immediately to **Final Handoff**.
        - If this is an **isolated blocker**: leave the item unchecked `- [ ]` in `docs/TASKS.md` and advance to the next independent task.
5. **Checkpoint & Persist:**
   - Mark the task `- [x]` in `docs/TASKS.md`.
   - Create or append any notable architectural, dependency, or fallback choices to `DECISIONS.md`.
   - Run `git status` and verify `local.properties` and `app/build/` outputs are not staged.
   - Commit with a clear conventional message (`feat(scope): ...`, `test(scope): ...`, `chore(scope): ...`).
   - Immediately run `git push -u origin <active-branch>` (never `--force`).
   - **Push Failure Fallback:** If `git push` fails due to a transient network/remote error, do **not** treat it as a task failure or reset the green commit. Retain the local commit, retry `git push -u origin <active-branch>` on the next task checkpoint and at Final Handoff, and record any remaining unpushed commits in `docs/HANDOFF_REPORT.md`.

## Final Handoff (`docs/HANDOFF_REPORT.md`)
When all tasks are checked off (or when halting early due to a foundational blocker):
1. Run the complete audit procedure in `.agents/skills/android-audio-verify/SKILL.md` (unless halted at `T0.1`).
2. Write or update `docs/HANDOFF_REPORT.md` containing:
   - **Overall Status:** Complete / Partially Complete / Blocked.
   - **Implementation Summary:** Completed milestones, modules, and commit SHAs.
   - **Software Verification Evidence:** Exact Gradle commands run, test pass/fail counts, lint status, debug APK path, and manifest `INTERNET` / `MANAGE_EXTERNAL_STORAGE` audit results.
   - **Dependencies & Licenses:** Table of every added library and its license.
   - **Audio & Volume Safety Status:** Clear split between *Verified (Code/Unit-Test Level)* and *Unverified (Awaiting Physical POCO F4 + KZ EDX Pro 2 Testing)*.
   - **Blockers & Risks:** Summary of `BLOCKED.md` (if any) and remaining technical limitations.
   - **Priority Hardware Verification Checklist:** Concrete checks for the user to run on POCO F4.
3. Commit and push `docs/HANDOFF_REPORT.md` to the active feature branch (if push fails due to transient network/remote issues, leave the commit intact locally and mention it in the final summary).