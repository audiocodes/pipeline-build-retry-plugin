# How it works

Pictures of what the plugin does in common situations. The rules, option names and exact log lines live in the [README](../README.md); each section links to the part it illustrates.

1. [A build's life with retries](#1-a-builds-life-with-retries)
2. [The decision](#2-the-decision)
3. [A retry chain up to the limit](#3-a-retry-chain-up-to-the-limit)
4. [Aborted builds](#4-aborted-builds)
5. [A lost agent](#5-a-lost-agent)
6. [The regular expression gate](#6-the-regular-expression-gate)
7. [`buildRetryDecision()` in `post`](#7-buildretrydecision-in-post)
8. [A point of no return](#8-a-point-of-no-return)
9. [A newer build under `abortPrevious`](#9-a-newer-build-under-abortprevious)
10. [Dry run and the controller's off switch](#10-dry-run-and-the-controllers-off-switch)
11. [Where the options come from](#11-where-the-options-come-from)
12. [Events](#12-events)

## 1. A build's life with retries

The plugin does nothing while the build runs. It decides once, after the build has ended and every `post` block has run, and a retry is a new build of the same job.

```text
build #41 runs: its stages, then every post block
    |
    v
build #41 ends with its final result
    |
    v
the plugin decides (section 2)
    |
    +-- no retry --> log: FAILURE → no retry (<reason>)
    |
    +-- retry -----> log: retry 1/3 queued (item N)
                       |
                       |  waits in the queue for the delay,
                       |  counted from the end of build #41
                       v
                     build #42 starts
                       cause: Build retry 1/3 after build #41 failed
                       same parameters, a retry badge in the build history
                       env:   BUILD_RETRY_COUNT=1, BUILD_RETRY_MAXCOUNT=3,
                              BUILD_RETRY_BUILD_NUMBER=41
```

README: [Decision order](../README.md#3-decision-order), [Environment variables](../README.md#5-environment-variables), [Log lines](../README.md#6-log-lines).

## 2. The decision

Read from the top: the first gate that says no decides. The name in brackets is the reason the log prints.

```text
build with buildRetry ends
  |
  |-- retries turned off on the controller? ------ yes --> no decision, nothing queued
  |-- a retry already scheduled for this build? -- yes --> no retry (already-scheduled)
  |-- options invalid? --------------------------- yes --> no retry (invalid-config: ...)
  |-- point of no return passed (section 8)? ----- yes --> no retry (point-of-no-return: ...)
  |-- a Replay, with skipReplays on? ------------- yes --> no retry (replay-skipped)
  |-- the result:
  |      SUCCESS --------------------------------------> no retry (success)
  |      UNSTABLE, without rerunIfUnstable ------------> no retry (unstable-not-enabled)
  |      ABORTED, kind not allowed (section 4) --------> no retry (aborted-<kind>-not-enabled)
  |      FAILURE or NOT_BUILT, or allowed above: go on
  |-- a neverRetryPatterns entry found? ---------- yes --> no retry (never-retry-matched: pattern #N ...)
  |      beats agent loss, the regexp and the abort policy; same scope as regexpScope
  |-- checkRegexp on and no match (section 6)? -- yes --> no retry (regexp-not-found, ...)
  |      skipped when rerunOnAgentLoss is on and agent loss is seen (section 5)
  |-- maxSchedule retries already done? ---------- yes --> no retry (max-reached)
  |
  v
retry after the delay
```

README: [Decision order](../README.md#3-decision-order).

## 3. A retry chain up to the limit

With `maxSchedule: 2` and `delay: fixedDelay(60)`. Each retry carries its place in the chain.

```text
#10  started by a person     FAILURE --> retry 1/2 in 60s
       |
       |  60 s after #10 ends
       v
#11  BUILD_RETRY_COUNT=1     FAILURE --> retry 2/2 in 60s
       |
       |  60 s after #11 ends
       v
#12  BUILD_RETRY_COUNT=2     FAILURE --> no retry (max-reached)

#13  started by a person     the count starts again at 0
     (Build with Parameters  FAILURE --> retry 1/2 in 60s
      or Rebuild)
```

A retry that succeeds ends the chain. Two chains in the same job are counted apart.

README: [Decision order](../README.md#3-decision-order) (Counter), [Options](../README.md#2-options).

## 4. Aborted builds

An ABORTED build retries only when `rerunIfAborted` allows what aborted it. The plugin names one kind; the first match wins.

```text
ABORTED build: what aborted it?
  1. a person or the API pressed Stop ......................... user
  2. Gerrit Trigger aborted it for a new patch set
     or an abandoned change, or the upstream build that
     started it with `build` was cancelled .................... superseded
  3. the agent was removed, its pod deleted or evicted,
     or its channel closed (section 5) ........................ infra
  4. a timeout step ran out ................................... timeout
  5. none of these ............................................ unknown
```

| Kind | Retried when `rerunIfAborted` is |
|---|---|
| `user` | `'always'` |
| `superseded` | never, not even with `'always'` or `rerunOnAgentLoss` |
| `infra` | `'infra'`, `'infraOrTimeout'` or `'always'`; also with `rerunOnAgentLoss: true` |
| `timeout` | `'timeout'`, `'infraOrTimeout'` or `'always'` |
| `unknown` | `'always'` |

The default, `'never'`, retries no ABORTED build.

README: [Decision order](../README.md#3-decision-order), [Options](../README.md#2-options).

## 5. A lost agent

When an agent goes away mid-build, the build can end ABORTED or FAILURE. With the Kubernetes plugin installed, the plugin also reads why the pod ended.

```text
an agent goes away mid-build
  |
  |-- Kubernetes says every failing agent's pod ended OOMKilled,
  |   Completed or DeadlineExceeded?
  |      yes --> not agent loss: a retry would fail the same way,
  |              so the build is judged like any other error
  v
agent loss
  |
  |-- the build ended ABORTED --> kind infra (section 4)
  |
  +-- the build ended FAILURE --> retried like any failure;
                                  with rerunOnAgentLoss the regexp gate is skipped too
```

A step that needs an agent but runs outside any `node` block fails with the same "missing context" error an agent loss can cause. Only the one inside `node` counts as agent loss; outside, it is a script error and the build is judged like any other failure.

Recipe: `buildRetry(maxSchedule: 2, rerunIfAborted: 'infra', rerunOnAgentLoss: true)`.

README: [Recipes](../README.md#8-recipes), [Options](../README.md#2-options).

## 6. The regular expression gate

With `checkRegexp: true`, a failure retries only when `regexpForRerun` matches a log line. `regexpScope` picks what is read.

```text
checkRegexp: true
  |
  |-- regexpScope 'build' (the default) ---> read the whole console log
  |
  |-- regexpScope 'failedStages':
  |      no top-level stage failed --------> read the whole console log
  |      a top-level stage failed ---------> read only its step logs, the failing step
  |                                          first, without stack frames or the output
  |                                          of passing housekeeping steps
  v
does a line match, within regexpTimeoutMs?
  yes --> go on to the limit
  no  --> no retry (regexp-not-found, regexp-timeout, regexp-invalid
          or regexp-log-unreadable)
```

Lines that contain `[Build Retry]` are never matched, so a pattern can't match the plugin's own output.

README: [Options](../README.md#2-options), [Limitations](../README.md#9-limitations).

## 7. `buildRetryDecision()` in `post`

The step decides early, so `post` can send a failure email only when no retry will follow. The plugin still decides at the end, and reuses the early decision when nothing changed.

```text
build #8
  a stage fails
  post: def d = buildRetryDecision()
        d.willRetry: true, d.reason: retry -----> skip the email: a retry follows
  the build ends and the plugin decides:
        same result, no new point of no return --> using the decision recorded by
                                                   buildRetryDecision()
        result changed after the step ----------> deciding again: recorded at UNSTABLE,
                                                   ended FAILURE

build #9, the last retry of the chain
  post: def d = buildRetryDecision()
        d.willRetry: false, d.reason: max-reached --> send the email
```

README: [Steps](../README.md#4-steps), [Jenkinsfile checklist](../README.md#10-jenkinsfile-checklist).

## 8. A point of no return

A build that has published or deployed something must not run again. `buildRetryPointOfNoReturn` marks that moment.

```text
stage('Test')
    tests fail here ...................... retried as usual
stage('Deploy')
    buildRetryPointOfNoReturn('deploying')
    the deploy fails here ................ no retry (point-of-no-return: deploying)
```

README: [Steps](../README.md#4-steps).

## 9. A newer build under `abortPrevious`

With `disableConcurrentBuilds(abortPrevious: true)`, the newest build wins.

```text
#41 FAILURE --> retry 1/3 queued, waiting for the delay
      |
      |  a person, an SCM change or Gerrit starts #42 during the delay
      v
#42 starts and cancels the waiting retry
    log of #42: pending retry 1 of build #41 cancelled: this build supersedes it
                (abortPrevious)

#41 ends after #42 has already started
    log of #41: retry 1/3 skipped: a newer build of this job started (abortPrevious)
```

README: [Log lines](../README.md#6-log-lines).

## 10. Dry run and the controller's off switch

Both keep retries from being queued, at different levels.

```text
build with buildRetry ends
  |
  |-- "Retry builds on this controller" is off (an administrator's switch)
  |      --> one log line, no decision, nothing queued; for every job
  v
the plugin decides (section 2)
  |
  |-- dryRun: true on this job
  |      --> log: FAILURE → would retry 1/3 in 300s (dry run); nothing queued
  v
retry queued
```

Turning the switch off leaves retries already in the queue where they are. Use `dryRun: true` to try new options on one job; use the switch in an incident.

README: [Global configuration](../README.md#7-global-configuration), [Recipes](../README.md#8-recipes).

## 11. Where the options come from

The Jenkinsfile and the job's configuration page write the same job setting, and the plugin reads it when each build ends. Use one source per job.

```text
Declarative   options { buildRetry(...) }  ---+
Scripted      properties([buildRetry(...)]) ---+--> the job's buildRetry setting
Job page      "Retry build after failure"  ---+         |
                                                        v
                                              read when each build ends
```

- **Declarative** writes it on every run, so edits made on the page are lost at the next build. Removing `buildRetry` from `options` removes the setting at the next run. A page setting stays as long as the Jenkinsfile never mentions `buildRetry`.
- **Scripted `properties([…])`** replaces the job's properties, so a page setting not listed there can be removed.
- **The page** suits jobs whose script is not in a repository. Set **Maximum number of retries** there: it starts at 0, which means unlimited.
- Multibranch branch jobs have no editable page; use the Jenkinsfile.
- A point of no return, `buildRetryDecision()` and options computed at run time exist only in the Jenkinsfile; see [What only the Jenkinsfile can do](../README.md#what-only-the-jenkinsfile-can-do).

README: [Quick start](../README.md#1-quick-start), [Jenkinsfile checklist](../README.md#10-jenkinsfile-checklist).

## 12. Events

With `sendEvents` on and an events endpoint set, each moment of a retry chain sends one event over OTLP/HTTP. With `maxSchedule: 2`, where the second retry passes:

```text
#10  started by a person, FAILURE
       decision         outcome queued, reason retry, cause_kinds user
       |
       v
#11  starts             retry_started   retry 1/2, root #10, waited 60 s
     FAILURE
       decision         outcome queued, reason retry
       |
       v
#12  starts             retry_started   retry 2/2, root #10
     SUCCESS
       decision         outcome no-retry, reason success
       chain_ended      recovered after 3 attempts, root #10
```

A waiting retry that is cancelled sends `retry_cancelled` and `chain_ended` `cancelled`: with reason `superseded` when a starting build cancels it under `abortPrevious`, and `queue-cancelled` when anyone else removes it from the queue. A build that is never retried sends only its decision. A build that another plugin already re-queued (`requeued-elsewhere`) sends its decision with the copy's queue item or build number and no `chain_ended`, because the copy carries the chain on. Without the OpenTelemetry API plugin, without an endpoint, or with `sendEvents` off, nothing is sent and nothing else changes.

Apart from builds, the plugin can also say which jobs use it, when **Send configured jobs** (`sendJobConfig`) is on as well. `build_retry.job_config` carries one job's settings. The full list goes out once all jobs are loaded, every hour, and when `sendEvents` or `sendJobConfig` is turned on or `enabled` changes. A single job's event goes out when a save changes its settings, so a change from the job page, `options {}` or `properties()` shows at once; saves that change nothing else in the plugin's settings send nothing. A job that loses `buildRetry`, is deleted or is renamed away sends `configured=false`, and a full list also sends it, with reason `missing`, for a job it sent as configured before that went without its own event, including one removed while the list was off (remembered until the controller restarts). Turning `sendJobConfig` off sends no removals, so a dashboard that keeps each job's last event within its time range, and drops jobs whose last event says `configured=false`, keeps the last list it received and shows how old each row is.

README: [Events](../README.md#events-opentelemetry).
