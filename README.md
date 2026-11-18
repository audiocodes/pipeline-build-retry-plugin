# Pipeline Build Retry

Retry a Pipeline build after it fails: when the build ends, the plugin decides whether to queue a new build of the same job, waits for a delay, and keeps a retry counter across the chain. It uses the familiar freestyle retry options and defaults (maximum retries, fixed or progressive delay, a regular expression on the log). It also judges what aborted a build (a person, a `timeout`, or a lost agent) and reads agent-loss and Kubernetes pod evidence.

It acts only on Pipeline jobs, multibranch branch jobs included, and only on jobs that turn it on. It never retries inside a build: for that, use the `retry` step.

## Install

1. Download the `.hpi` file of the latest release from [GitHub releases](https://github.com/audiocodes/pipeline-build-retry-plugin/releases), then upload it in **Manage Jenkins → Plugins → Advanced settings → Deploy Plugin**. It needs Jenkins 2.555.3 or newer. Update the plugins it depends on first: one older than the version this release depends on stops it from loading.
2. Restart Jenkins when the plugin manager asks. Every update of the plugin needs a restart, because Jenkins does not replace a loaded plugin.
3. Turn it on per job with `buildRetry(…)` ([Quick start](#1-quick-start)). Installing it changes no job.

The Kubernetes plugin is optional. When it is installed, the plugin also reads why an agent pod ended, for example `OOMKilled`.

The [OpenTelemetry API](https://plugins.jenkins.io/opentelemetry-api/) plugin is optional too, and needed only to [send events](#events-opentelemetry). When it is installed, it must be at least the version this release depends on, or Jenkins will not load this plugin.

## Contents

1. [Quick start](#1-quick-start)
2. [Options](#2-options)
3. [Decision order](#3-decision-order)
4. [Steps](#4-steps)
5. [Environment variables](#5-environment-variables)
6. [Log lines](#6-log-lines)
7. [Global configuration](#7-global-configuration)
8. [Recipes](#8-recipes)
9. [Limitations](#9-limitations)
10. [Jenkinsfile checklist](#10-jenkinsfile-checklist)
11. [Coming from the freestyle Naginator plugin](#11-coming-from-the-freestyle-naginator-plugin)

Also: [Support](#support), [License](#license).

Diagrams of what the plugin does in common situations are in [How it works](docs/how-it-works.md).

## 1. Quick start

### Declarative

```groovy
pipeline {
    agent any
    options {
        buildRetry(maxSchedule: 2, delay: fixedDelay(60),
                   checkRegexp: true, regexpForRerun: 'Connection reset|HTTP 50[23]')
    }
    stages {
        stage('Build') { steps { sh 'make' } }
    }
}
```

### Scripted

```groovy
properties([buildRetry(maxSchedule: 2, rerunIfAborted: 'infra')])
node {
    sh 'make'
}
```

### Job page

Check **Retry build after failure** in the job configuration. The page edits the same options; list and map options are text areas, one entry per line.

That is all a job needs: when a build of it ends, the plugin decides and, if it retries, queues the next build. Nothing has to run in `post`.

### What only the Jenkinsfile can do

The page holds one fixed set of options for the job, read when each build ends. These need the Jenkinsfile:

| Need | Why the page can't | In the Jenkinsfile |
|---|---|---|
| Never retry a build once it has published, deployed or triggered something | The page can't tell how far a build got | `buildRetryPointOfNoReturn('…')` right before that step ([Steps](#4-steps)) |
| Notify about a failure only when no retry follows | Notifications run inside the build, before the plugin decides | `buildRetryDecision()` in `post` ([Steps](#4-steps)) |
| Options for a multibranch branch job | Branch jobs have no editable page | `options {}` or `properties([…])` |
| Options that depend on the branch, a parameter or anything else known at run time | The page holds one value for every build | Scripted `properties([buildRetry(…)])` with computed values |
| Retry one flaky step without running the whole build again | The plugin retries whole builds | Pipeline's own `retry(n) { … }` step |
| Options reviewed and versioned with the code | Page edits are not in source control | `options {}` or `properties([…])` |

The two steps are not job options, so they work with options set on the page, and Declarative leaves a page setting alone as long as the Jenkinsfile never mentions `buildRetry`.

## 2. Options

All options are optional. An unknown option fails the build: Declarative names the closest option ("did you mean"); Scripted reports `Unknown parameter(s) found`. An invalid value fails it with the option's name, and the message never includes a regular expression.

| Option | Type | Default | What it does |
|---|---|---|---|
| `maxSchedule` | int | `0` = unlimited (with a warning) | Maximum number of retries in a chain |
| `delay` | `fixedDelay`, `progressiveDelay` or `listDelay` | `progressiveDelay(increment: 300, max: 10800)` | Wait before the retry starts, counted from the build's end |
| `rerunIfUnstable` | boolean | `false` | Retry UNSTABLE builds as well as failures |
| `checkRegexp` | boolean | `false` | Retry only if `regexpForRerun` matches a log line |
| `regexpForRerun` | string | `''` | Java regular expression, matched one line at a time with `find()`; blank means every failure retries |
| `regexpScope` | `'build'` \| `'failedStages'` | `'build'` | Search the whole console log, or only the failed top-level stages' step logs |
| `regexpTimeoutMs` | int | the global setting (30000) | Time limit for the whole log scan |
| `neverRetryPatterns` | list of regexps, at most 20 | `[]` | When any of them is found in the log, the build is never retried, whatever else says it should be; searched where `regexpScope` says |
| `rerunIfAborted` | `'never'` \| `'infra'` \| `'timeout'` \| `'infraOrTimeout'` \| `'always'` | `'never'` | Whether an ABORTED build retries, by what aborted it |
| `rerunOnAgentLoss` | boolean | `false` | With agent-loss evidence, retry even when the regexp gate says no; also retries an ABORTED build whose abort kind is `infra` |
| `agentLossPatterns` | list of regexps | built-in list | Log lines that count as agent loss; setting it replaces the list |
| `agentLossPatternsExtra` | list of regexps | `[]` | Added to `agentLossPatterns` |
| `skipReplays` | boolean | `true` | Never retry a Replay |
| `gerritCause` | `'drop'` \| `'keep'` | `'drop'` | Whether the retry keeps the Gerrit Trigger cause |
| `dropCauses` | list of class names | `[]` | Other causes not to copy to the retry (simple or full class names) |
| `parameterOverrides` | map, name → value | `[:]` | Change parameters on the retry only; every other parameter is copied |
| `badge` | boolean | `true` | Show the retry badge in the build history |
| `dryRun` | boolean | `false` | Decide and log, never queue |
| `verbose` | boolean | `false` | Log one detail line per gate, plus the copied causes and parameter names |

**Delays.** `n` is the number of retries already done.

- `fixedDelay(60)`: always 60 seconds.
- `progressiveDelay(increment: 300, max: 10800)`: `increment × (n+1)(n+2)/2`, capped at `max`. The default gives 300, 900, 1800, 3000, 4500, 6300, 8400, then 10800.
- `listDelay([30, 90, 180])`: the n-th value; the last one repeats.

**`regexpScope: 'failedStages'`** reads the step logs of the failed top-level stages, the failing step first, plus the `ERROR:` line Jenkins prints for an `error` or failed `sh`. It skips stack-frame lines and the output of passing housekeeping steps (`archiveArtifacts`, `stash`, `junit` and the like). With no failed stage it reads the whole log. Agent-loss patterns always read the whole log.

**`neverRetryPatterns`** lists log text that means a real bug, such as `COMPILATION ERROR`, `error: cannot find symbol`, or the text of the job's own `error('…')` for a bad parameter. Avoid catch-all text such as Maven's `BUILD FAILURE`: flaky failures print it too. The patterns follow the same rules as `regexpForRerun`: Java regular expressions, case-sensitive (`(?i)` to ignore case), matched inside one line, on the masked log, and searched where `regexpScope` says. Unlike `regexpForRerun`, they also read lines that contain `[Build Retry]`: a build can print that prefix itself, so skipping those lines would let it hide a stop line. Avoid patterns that match the plugin's own text, such as the decision step's preview and details, `pending retry`, or a `buildRetryPointOfNoReturn` reason. They share its timeout. A scan that times out or cannot read the log does not block a retry; a slow `regexpForRerun` can leave them unread. The decision names the pattern by its number, never its text.

## 3. Decision order

The first gate that says no decides. The reason in brackets is what the decision line prints.

Before any gate: when retries are turned off for the controller ([Global configuration](#7-global-configuration)), nothing is scanned or decided.

1. A retry for this build is already scheduled → no (`already-scheduled`).
2. The options are invalid → no (`invalid-config: …`).
3. `buildRetryPointOfNoReturn` ran → no (`point-of-no-return: <reason>`).
4. The build is a Replay and `skipReplays` is on → no (`replay-skipped`).
5. The result:
   - SUCCESS → no (`success`).
   - UNSTABLE → only with `rerunIfUnstable` (`unstable-not-enabled`).
   - FAILURE or NOT_BUILT → go on.
   - ABORTED → by what aborted it, against `rerunIfAborted` (`aborted-<kind>-not-enabled`):
     - `user`: a person or the API pressed Stop;
     - `superseded`: the Gerrit Trigger plugin aborted it because a new patch set was uploaded or the change was abandoned, or the upstream build that started it with the `build` step was cancelled. Never retried, even with `'always'` or `rerunOnAgentLoss` (`aborted-superseded`);
     - `infra`: the agent was removed, its pod was deleted or evicted, or its channel closed. A step inside `node` that lost its agent context (`MissingContextVariableException` for `FilePath`, `Launcher`, `Computer` or `Node`) counts too; the same error from a step outside any `node`, such as `archiveArtifacts` in a `post` under `agent none`, is a script error, not infra. When every Kubernetes termination reason of the failing agents is `OOMKilled`, `Completed` or `DeadlineExceeded`, it is not infra: a retry would fail the same way;
     - `timeout`: a `timeout` step ran out;
     - `unknown`: none of these; retried only with `'always'`.
6. A `neverRetryPatterns` entry is found → no (`never-retry-matched: pattern #N at console line L`; `in the failed stages' logs` under `failedStages`, `in a stage error line` for the `ERROR:` lines added after the console). This beats every retry signal below and the abort policy above. Not checked when the retry limit is already reached.
7. With `rerunOnAgentLoss` and agent-loss evidence, the regexp gate is skipped.
8. With `checkRegexp`: no match → no (`regexp-not-found`); also `regexp-timeout`, `regexp-invalid`, `regexp-log-unreadable`, `regexp-not-scanned`.
9. `maxSchedule` retries already done → no (`max-reached`).
10. Otherwise it retries after the delay for this retry number.

**Counter.** Each retry carries its place in the chain. A build a person starts (Build with Parameters, Rebuild) starts again at 0. Two chains in the same job are counted apart.

**When the decision is made.** When the build ends, with the job's options as they are then. If `buildRetryDecision()` ran during the build and the build ended with the same result and no new point of no return, that recorded decision is used as it is.

## 4. Steps

### `buildRetryDecision()`

Decides now, with the build's current result, records the decision on the build, and returns it as a map. Use it in `post` to word a notification.

```groovy
post {
    always {
        script {
            def d = buildRetryDecision()
            if (currentBuild.currentResult != 'SUCCESS' && !d.willRetry) {
                mail to: 'team@example.com', subject: "${env.JOB_NAME} #${env.BUILD_NUMBER} failed", body: d.reason
            }
        }
    }
}
```

| Key | Type | Meaning |
|---|---|---|
| `willRetry` | boolean | A retry will be queued when the build ends. Always `false` in a dry run |
| `wouldRetry` | boolean | The decision before `dryRun` |
| `reason` | string | `retry`, or why not (see [Decision order](#3-decision-order)) |
| `result` | string | The result that was judged |
| `abortKind` | string | `user`, `superseded`, `infra`, `timeout` or `unknown`; only for ABORTED |
| `retryCount`, `nextRetryCount`, `maxSchedule` | int | Retries done, the number of the next retry, the limit |
| `delaySeconds` | int, or null when no retry is planned | The delay, counted from the build's end |
| `regexChecked`, `regexMatched` | boolean | Whether the regexp gate ran, and whether it matched |
| `agentLoss`, `agentLossOverride` | boolean | Agent-loss evidence was seen; it skipped the regexp gate |
| `counterReset` | boolean | A person started this build, so the count went back to 0 |
| `neverRetryPattern` | int, or null | The number of the `neverRetryPatterns` entry that stopped the retry |
| `dryRun` | boolean | The option in effect |
| `warnings` | list of strings | Configuration warnings |

On a job without `buildRetry` it returns `willRetry: false` with reason `not-configured` and records nothing; with retries turned off for the controller, reason `disabled`, also recording nothing. In a Scripted `catch` block the current result is still SUCCESS unless the script set it; the decision at the build's end sees the real result and wins.

### `buildRetryPointOfNoReturn('reason')`

From this step on, the build is never retried. Call it right before the first step that publishes, deploys or triggers something that must not happen twice.

```groovy
stage('Build') { steps { sh './build.sh' } }         // a lost agent here: retried as usual
stage('Publish') {
    steps {
        buildRetryPointOfNoReturn('publishing')
        sh './push-images.sh'                        // a failure from here on: no retry
    }
}
stage('Tag release') { steps { sh './tag.sh' } }
```

Why it matters: a retry runs the whole build again, publish included. A release whose version is computed from existing tags computes the same version when the tag was not yet written, and overwrites what was published with a new build; once the tag exists, it computes the next version and releases the same commit twice. A failure before the step still retries.

Only the Jenkinsfile can mark this moment: the plugin decides after the build has ended, from options that are the same for every build. The step needs no `buildRetry` in the Jenkinsfile; it works with options set on the job page. The log prints `point of no return: <reason>; this build will not be retried`, and the end decision says `no retry (point-of-no-return: <reason>)`.

## 5. Environment variables

Set on retried builds only, through `env` and every `sh`/`bat` step:

| Variable | Meaning |
|---|---|
| `BUILD_RETRY_COUNT` | Which retry this is: 1, 2, … |
| `BUILD_RETRY_MAXCOUNT` | `maxSchedule` when the retry was queued (0 = unlimited) |
| `BUILD_RETRY_BUILD_NUMBER` | The number of the build this one retries |

## 6. Log lines

Every line starts with `[Build Retry]`. `regexpForRerun` and the agent-loss scan skip lines that contain it, so they can't match the plugin's own output. `neverRetryPatterns` read them too, so a build that prints the prefix cannot hide a stop line.

| Line | Meaning |
|---|---|
| `FAILURE → retry 1/3 in 300s` | The decision: a retry. The tail can add `(regexp matched)`, `(agent loss)`, `(count reset by user start)` |
| `FAILURE → no retry (max-reached)` | No retry, and why |
| `ABORTED/timeout → retry 1/2 in 10s` | An ABORTED decision names the abort kind |
| `FAILURE → would retry 1/3 in 300s (dry run)` | Dry run: nothing is queued |
| `retry 1/3 queued (item 4521)` | The retry is in the queue |
| `…; cancelled if another build of this job starts first (abortPrevious)` | Under `abortPrevious`, a newer build cancels the waiting retry |
| `pending retry 1 of build #41 cancelled: this build supersedes it (abortPrevious)` | Printed by the newer build that cancelled it |
| `retry 1/3 skipped: a newer build of this job started (abortPrevious)` | The build was superseded |
| `retry 1/3 skipped: this build was already re-queued by another plugin (queue item 4522)` | Another plugin, typically a cloud plugin that re-runs a build when it loses the agent, already queued or started a copy of this build (`(build #42)` once it started); only that copy runs |
| `retry 1/3 would be skipped: this build was already re-queued by another plugin (queue item 4522)` | Dry run: a real retry would also have been skipped, because another plugin already re-queued this build |
| `retry 1/3 not queued: the job is not buildable` | The job is disabled |
| `retries are turned off on this controller; no decision (an administrator turns them back on with …)` | The global switch is off ([how to flip it](#turning-retries-off-and-on)) |
| `using the decision recorded by buildRetryDecision()` | The end decision reused the preview |
| `deciding again: recorded at UNSTABLE, ended FAILURE` | The result changed after the preview |
| `point of no return: <reason>; this build will not be retried` | `buildRetryPointOfNoReturn` |
| `WARNING …` | A configuration warning, always printed |
| `  <detail>` | With `verbose: true` only: one line per gate |

## 7. Global configuration

**Manage Jenkins → System → Pipeline Build Retry** holds these settings:

- **Retry builds on this controller** (`enabled`, on by default). Turn it off to stop every retry at once, without a restart: when a build with `buildRetry` ends, the plugin scans nothing, decides nothing and queues nothing, and logs one line. Retries already waiting in the queue stay there; cancel them in the queue if needed. Under `abortPrevious`, a newer build still cancels a waiting retry. Turning it back on applies to every build that ends afterwards, including one whose `buildRetryDecision()` returned `disabled` while it was off. Use it when a plugin version misbehaves, until the fixed version is installed at the next planned restart.
- The default time limit for regular-expression scans. A job's `regexpTimeoutMs` overrides it.
- **Send events** (`sendEvents`, off by default), with **Send configured jobs**, **Events endpoint**, **Events token**, **Events service name** and **Events resource attributes**. See [Events](#events-opentelemetry) below.

### Turning retries off and on

An administrator runs one line in the script console (**Manage Jenkins → Script Console**):

```groovy
io.jenkins.plugins.pipelinebuildretry.BuildRetryGlobalConfiguration.get().setEnabled(false)
```

and this one to turn retries back on:

```groovy
io.jenkins.plugins.pipelinebuildretry.BuildRetryGlobalConfiguration.get().setEnabled(true)
```

The line takes effect at once and saves only the plugin's own settings file, so it also survives a restart. Without a browser, send the same line to the controller's `/scriptText` endpoint with an administrator's API token:

```bash
curl -X POST -u ADMIN:API_TOKEN --data-urlencode 'script=io.jenkins.plugins.pipelinebuildretry.BuildRetryGlobalConfiguration.get().setEnabled(false)' https://CONTROLLER/scriptText
```

The alternative is the checkbox on **Manage Jenkins → System**. Saving that page saves every setting on it again, not only this one, so prefer the line above in an incident. With Configuration as Code, also set `enabled` in the YAML, or the next reload undoes the change.

With Configuration as Code:

```yaml
unclassified:
  buildRetry:
    enabled: true
    regexpTimeoutMs: 30000
    sendEvents: false
    sendJobConfig: false
    eventsEndpoint: https://COLLECTOR:4318
    eventsCredentialsId: events-token
    eventsServiceName: jenkins
    eventsResourceAttributes: deployment.environment.name=prod
```

### Events (OpenTelemetry)

With `sendEvents` on, the plugin sends a structured event at each moment below, so retries can be counted, charted and alerted on without reading build logs. Events are OpenTelemetry log records with an event name, sent over OTLP/HTTP (protobuf) to **Events endpoint**:

- **Send configured jobs** (`sendJobConfig`, off by default): also send `build_retry.job_config`, the list of jobs that use the plugin with their settings, every hour and when a job's settings change. It works only with **Send events** on and an endpoint set; the form warns otherwise. Ticking it sends the full list at once. Unticking it only stops new events: nothing is sent to remove jobs, so a dashboard keeps showing the last list it received.
- **Events endpoint** (`eventsEndpoint`): the collector's address, such as `https://COLLECTOR:4318`; `/v1/logs` is added. Blank sends nothing.
- **Events token** (`eventsCredentialsId`): optional, a Secret text credential sent as `Authorization: Bearer`. Use the System scope. The token goes only over `https`, or over `http` to a loopback address (`localhost`, `127.x.x.x`, `[::1]`). With a token and a plain `http` endpoint on another host, the form shows an error and no events are sent; the controller log gets one WARNING naming the endpoint. A plain `http` endpoint without a token still works.
- **Events service name** (`eventsServiceName`): the `service.name` resource attribute, `jenkins` by default. Each event also carries `service.namespace=jenkins` and `jenkins.url`.
- **Events resource attributes** (`eventsResourceAttributes`): optional extra resource attributes as `key=value` entries, one per line or separated by commas, such as `deployment.environment.name=prod`; at most 20, values without commas. They may replace `service.namespace` and `jenkins.url`; `service.name` always comes from its own field. Loki indexes some well-known ones, such as `deployment.environment.name`, as labels, so give them few values.

Sending needs the [OpenTelemetry API](https://plugins.jenkins.io/opentelemetry-api/) plugin, which only provides the libraries. The full OpenTelemetry plugin is not needed and is not used: the plugin keeps its own small exporter, so it sends nothing but these events and adds no work to builds or web requests. Its queue holds at most 1024 events and drops new ones when full; batches go out every 5 seconds with a 10-second timeout. A slow or down collector costs only events: decisions and retries never wait for it, and the events of failed batches are dropped, not sent later. While it is down, the controller log gets a one-line WARNING `Failed to export logs` per failed batch, which the OpenTelemetry SDK throttles. The SDK logs these at SEVERE with a stack trace; once events are sent, the plugin lowers them to WARNING without the trace. That logger (`io.opentelemetry.exporter.internal.http.HttpExporter`) is shared by every plugin that uses the OpenTelemetry API plugin's OTLP/HTTP exporter, so their unreachable-collector errors are lowered too. Errors with an HTTP status, such as a 401 for a wrong token, are already warnings and stay as they are.

The full OpenTelemetry plugin can be installed alongside. The two send separately: these events never use its endpoint, token or service name, and its `OTEL_*` variables and `otel.*` properties do not change them. If the events stop because a newer OpenTelemetry API plugin no longer fits, the controller log says so once, as a WARNING naming the sink.

| Event | When | Main attributes |
|---|---|---|
| `build_retry.decision` | A build of a job with `buildRetry` ends and the plugin decides, or would have decided while retries are turned off | `build_retry.outcome` (`queued`, `no-retry`, `dry-run`, `superseded`, `requeued-elsewhere`, `not-buildable`, `not-queued`, `cancelled`, `error`, `disabled`), `build_retry.reason` and `build_retry.reason_detail`, `ci.pipeline.run.result`, `build_retry.abort_kind`, retry count, next and max, delay, dry run, regexp and agent-loss flags, `build_retry.never_retry_checked`, `build_retry.never_retry_matched` and `build_retry.never_retry_pattern_index` (the number, never the text), failed stages, termination reasons, error classes, `build_retry.failure_class`, the first failing stage, its failing step (`build_retry.error_step`) and `build_retry.error_fingerprint` with its `build_retry.error_basis`, the agents where the error started and their labels (`build_retry.failed_agents`, `build_retry.failed_agent_labels`), the point of no return, the log scan's figures, and what triggered the build: `build_retry.cause_kinds` (`user`, `upstream`, `scm`, `timer`, `remote`, `gerrit`, `replay`, `restart`, `branch-indexing`, `branch-event`, `retry`, `other`), `build_retry.causes`, the user, the upstream build and the Gerrit event and change; for `requeued-elsewhere`, the other plugin's copy: `build_retry.copy_queue_item_id` while it waits, else `build_retry.copy_build_number` |
| `build_retry.retry_started` | A retry this plugin queued starts | retry count and max, parent and first build numbers, the planned delay and the time actually waited since the parent ended |
| `build_retry.chain_ended` | A retry build ends with no further retry, or the chain's waiting retry is cancelled | `build_retry.chain_end` (`recovered` for SUCCESS, `exhausted` for `max-reached`, `cancelled` for a cancelled retry, with reason `superseded` and `build_retry.superseded_by` when a starting build cancelled it, or `queue-cancelled` when it was cancelled in the queue, `stopped` otherwise), attempts, the first build number, the time from the first build's start; a cancelled chain's event names its last build that ran. Not sent for `requeued-elsewhere`: the copy carries the chain on and its own end closes it |
| `build_retry.retry_cancelled` | A waiting retry is cancelled: under `abortPrevious` by a starting build, or in the queue by anyone else, such as a user, a script or another plugin | `build_retry.reason` (`superseded` or `queue-cancelled`), the cancelled retry's count, max and parent, how long it waited; for `queue-cancelled`, its queue item (`build_retry.queue_item_id`) and `build_retry.cancel_source`: `web` when a web request cancelled it, as from the queue's cancel button, the REST API or a script console run, else `internal` (a background thread, such as another plugin's). Jenkins tells the plugin about a cancel as the system user, so the event cannot name who cancelled; the controller's access log can |
| `build_retry.switch_changed` | `enabled`, `sendEvents` or `sendJobConfig` changes | the setting, old and new value, who changed it |
| `build_retry.job_config` | Only with **Send configured jobs** on. Which jobs use the plugin now: one event per job that has `buildRetry`, sent once all jobs are loaded, every hour, when `sendEvents` or `sendJobConfig` is turned on or `enabled` changes; and for one job when a save changes its settings (job page, `options {}` or `properties()`), when it loses `buildRetry`, or when it is deleted or renamed | `build_retry.configured` (false when the job lost `buildRetry`, was deleted, or was renamed away from that name, or, with reason `missing`, when a full list no longer finds a job it sent as configured before, such as one removed while the list was off, within the same controller run; such an event carries no settings), `build_retry.snapshot_reason` (`startup`, `hourly`, `events-on`, `job-config-on`, `switch-changed`, `changed`, `deleted`, `renamed`, `missing`), `ci.pipeline.url`, `build_retry.set_by` (`declarative-options`, `properties-step` or `job-config`), `build_retry.job_disabled`, `build_retry.retries_enabled`, and the settings: max retries, `build_retry.delay_type` and `build_retry.delay` (such as `fixed 60s`), `rerun_if_unstable`, `rerun_if_aborted`, `rerun_on_agent_loss`, `check_regexp`, `regexp_set` (whether a pattern is set; the pattern itself is never sent, not even as a hash), `regexp_scope`, `never_retry_count` (how many never-retry patterns; their text is never sent), `gerrit_cause`, `dry_run`, and counts or names only for agent-loss patterns, dropped causes and parameter overrides |

`build_retry.failure_class` is one of `user-abort`, `superseded` (cancelled by a newer build under `abortPrevious`, aborted by the Gerrit Trigger plugin for a new patch set or an abandoned change, or cancelled with the upstream build that started it), `controller-restart` (a step that cannot resume after a controller restart), `agent-oom` (the agent's container was OOMKilled), `agent-lost`, `timeout`, `unstable`, `aborted-unknown`, `not-built`, `script-failure` (a failing `sh`, `error` or similar step), `pipeline-error` (any other exception) or `unknown`. The first matching one wins, in that order, the same order the decision uses for abort kinds. `agent-lost` from log text alone appears only when the job's options make the plugin scan the log for agent loss. `build_retry.error_fingerprint` is a 12-character hash that stays the same when the same failure repeats, so the same fingerprint across a chain means the retries hit the same error again. It is built from the first failing stage's name, the step where that stage's failure started (its function name and arguments, such as the `sh` script) and a root-cause line from that step's own log; `build_retry.error_basis` says which: `log-line` (all three), `step` (the step's log has no usable line, as with `error`) or `finish-line` (no failing step found, so the stage and the line Jenkins prints when the build ends). The root-cause line is the first line of the last block of error-like lines (`error`, `fail`, `fatal`, `exception`, `traceback`, `cannot`, `denied`, `timeout`, `not found`, `refused`, `npm ERR!`), read from the last 64 KiB, at most 200 lines, of that step's log, after dropping shell trace lines, `[Pipeline]` markers, `script returned exit code`, stack frames and make's own `***` line; without one, the last line left. Before hashing, URLs, `user:password@`, `key=value` values, quoted strings, absolute paths and every word that holds a digit are masked, and the line is cut to 300 characters, so build numbers, pod names and workspace paths do not change it. The log is read only while events are on, once per decision of a build that did not pass. The line and the step's arguments are never sent, only their hash.

Every event also carries `ci.pipeline.id` (the job's full name), `ci.pipeline.run.number`, `ci.pipeline.run.url`, `ci.pipeline.run.queue_item_id` (the queue item the build started from, which matches the original's `build_retry.copy_queue_item_id` on a copy), the queue time and the duration so far. The record's body is a readable line such as `folder/job #41: FAILURE → retry 1/2 in 300s [queued]`. Strings are cut at 1 KB and lists at 20 entries, joined with `, `.

Each record also has a plain `event_name` attribute holding the event name, because Loki 3.7 and older drop the OpenTelemetry event name field. In Loki, `service.name` becomes the `service_name` label, other attributes become structured metadata (except a few well-known resource attributes, see **Events resource attributes**), and dots become underscores:

```logql
{service_name="jenkins"} | event_name="build_retry.decision" | build_retry_outcome="queued"
sum by (build_retry_chain_end) (count_over_time({service_name="jenkins"} | event_name="build_retry.chain_ended" [1d]))
```

A ready-made Grafana dashboard for these events, with import steps, is in [`dashboards/`](dashboards/README.md).

## 8. Recipes

**Recover from a lost or evicted Kubernetes agent**

```groovy
options { buildRetry(maxSchedule: 2, rerunIfAborted: 'infra', rerunOnAgentLoss: true) }
```

A lost pod can end the build ABORTED or FAILURE. `'infra'` covers the first. A FAILURE retries anyway unless a regexp gate says no; `rerunOnAgentLoss` makes it retry even then.

**Retry only on flaky network errors, judged from the failed stage**

```groovy
options {
    buildRetry(maxSchedule: 2, delay: listDelay([60, 300]), checkRegexp: true,
               regexpForRerun: 'Connection reset|Could not resolve host', regexpScope: 'failedStages')
}
```

**Retry flaky failures, never compile errors**

```groovy
options { buildRetry(maxSchedule: 2, rerunOnAgentLoss: true, neverRetryPatterns: ['COMPILATION ERROR', 'error: cannot find symbol']) }
```

**Change a parameter on the retry**

```groovy
options { buildRetry(maxSchedule: 1, parameterOverrides: [CLEAN_WORKSPACE: true]) }
```

**Roll out with a dry run**

```groovy
options { buildRetry(maxSchedule: 3, dryRun: true) }
```

The log shows `→ would retry 1/3 in 300s (dry run)`; nothing is queued.

## 9. Limitations

- **Builds that never complete normally.** A build whose `onCompleted` never fires (it failed to load after a restart, its resume was refused, or it was terminated with no execution) is not retried.
- **Other plugins that re-run builds.** Some cloud plugins re-queue a build themselves when its agent disconnects. They pass the build's own cause and parameter objects to the copy, and that is how the plugin sees it: a copy still in the queue, or among the next 10 builds of the job, means no retry (`requeued-elsewhere`). A copy made with new objects, or one made before a controller restart, is not recognised, so turn one of the two off for such a cloud. A copy of a retry build keeps the retry's count and first build, so the chain goes on in the copy: the copy sends its own `retry_started`, and the lost attempt is not counted in `attempts`. A copy of a first build starts a chain of its own.
- **No Retry link.** Use Replay or Rebuild.
- **Very long lines.** A line over 65,536 characters is scanned in pieces of that size that overlap by 1,024 characters. On such a line, `^`, `$` and `\b` can also match at a piece edge, and a match longer than 1,024 characters that crosses an edge is missed.
- **Log storage.** `regexpScope: 'failedStages'` reads step logs one at a time. With a log storage that buffers whole logs, or workflow-api older than 1398, it reads the whole log instead and says so in a detail line.
- **Concurrent chains.** In a job that runs builds concurrently, notifications that compare with the previous build (fixed, regression) may compare against another chain.
- **Gerrit.** With `gerritCause: 'keep'`, Gerrit Trigger reports the retry like a new build, which emails the change's reviewers. With `'drop'` (the default) it ignores the retry. `GERRIT_*` parameters are kept either way.
- **Unknown options in Scripted.** Scripted reports `Unknown parameter(s) found` without suggesting the closest name.

## 10. Jenkinsfile checklist

Follow these whenever a Jenkinsfile that uses this plugin is written or changed, by a person or by an AI coding agent.

1. Put `buildRetry(…)` in `options {}` (Declarative) or `properties([…])` (Scripted), or set it on the job page; use one of them per job. The steps take no options.
2. Set `maxSchedule`. `0` means unlimited.
3. Never echo or print `regexpForRerun`.
4. Call `buildRetryPointOfNoReturn('<what happened>')` right before the first step that publishes, deploys or triggers another job.
5. Notify about failures only when `!buildRetryDecision().willRetry`. Call it in `post`, after the result is final.
6. Read the retry number from `env.BUILD_RETRY_COUNT`; do not keep your own counter parameter.
7. Try new settings with `dryRun: true` first and check the `would retry` lines.
8. Don't write `currentBuild.description` to mark retries; the plugin adds a badge and a cause.

## 11. Coming from the freestyle Naginator plugin

| Naginator (freestyle) | This plugin |
|---|---|
| Post-build action "Retry build after failure" | `buildRetry(…)` job option |
| Maximum number of successive failed builds | `maxSchedule` |
| Delay: Fixed / Progressive | `fixedDelay(…)` / `progressiveDelay(…)`; also `listDelay(…)` |
| Rerun build for unstable builds as well as failures | `rerunIfUnstable` |
| Only rerun build if regular expression is found in output | `checkRegexp` + `regexpForRerun` |
| Global: timeout for regular expressions | Global configuration, or `regexpTimeoutMs` per job |
| Rerun build only for failed parts on the matrix | Not in this version |
| `NAGINATOR_COUNT`, `NAGINATOR_MAXCOUNT`, `NAGINATOR_BUILD_NUMBER` | `BUILD_RETRY_COUNT`, `BUILD_RETRY_MAXCOUNT`, `BUILD_RETRY_BUILD_NUMBER` |
| Retry link on a failed build | None; use Replay or Rebuild |

Retry chains do not carry over: a build retried by Naginator before the switch counts as a fresh start here.

## Changelog

Release notes are on [GitHub releases](https://github.com/audiocodes/pipeline-build-retry-plugin/releases).

## Issues

Report bugs and ideas in [GitHub issues](https://github.com/audiocodes/pipeline-build-retry-plugin/issues). Include the Jenkins and plugin versions, the job's `buildRetry(…)` options, and the build's `[Build Retry]` log lines; `verbose: true` adds the details behind each decision.

Report security vulnerabilities privately, as described in [SECURITY.md](SECURITY.md), not in a public issue.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to report issues, build, test and send changes, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). The plugin's layout and design decisions are in [AGENTS.md](AGENTS.md).

## Support

The plugin is provided under the MIT License on an "as is" basis, without warranty of any kind. The copyright holders and contributors make no commitment to support or maintain it, to fix reported problems, or to publish further releases. Issues and pull requests are looked at on a best-effort basis.

## License

MIT, on an "as is" basis; see [LICENSE.md](LICENSE.md). Parts derived from or based on the Naginator plugin are listed in [NOTICE.md](NOTICE.md).

Created by Igor Levin; see [ACKNOWLEDGEMENTS.md](ACKNOWLEDGEMENTS.md).
