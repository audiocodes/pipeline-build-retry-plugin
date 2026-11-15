# Grafana dashboard

`build-retry-events.json` is a Grafana dashboard for the plugin's events (README, "Events (OpenTelemetry)"). It reads them from Loki only. The panels cover:

- **Configured jobs:** every job that uses the plugin, with its settings, from each job's last `build_retry.job_config` event in the dashboard's time range (7 days by default). A job you add shows within seconds; a job that loses `buildRetry`, is deleted or renamed drops out when its event arrives, or at the next full list if it went without one. **Last sent** turns orange when a row is older than 2 hours: the controller has stopped sending the list (**Send configured jobs** unticked, events off, or the controller down) and the row shows its last known state. A controller that never had **Send configured jobs** on is not listed.
- **Overview:** decisions, retries queued, dry-run "would retry", chains recovered, exhausted or cancelled, warnings, and the recovery rate.
- **Decisions:** outcomes over time, chain ends, no-retry reasons, retries per job, abort kinds, triggers and results.
- **Retry chains:** recovery rate per job, attempts per chain, minutes spent in retry builds, planned delay against the actual wait, and chain duration.
- **Agents, log scan and errors:** agent loss, Kubernetes termination reasons, regexp gate problems, retries blocked by a never-retry pattern (per job and pattern number) and warnings.
- **Activity:** setting changes, and the latest events.

## Requirements

- Grafana 10 or newer, with a Loki data source.
- Loki ingesting OTLP logs, either directly at `/otlp` or through a collector such as Alloy or the OpenTelemetry Collector.
- **Send events** turned on in the plugin, with an endpoint that reaches that Loki.

The queries rely on these values:

- The plugin sends `service.namespace=jenkins` unless **Events resource attributes** replaces it; set the **Service namespace** variable to match.
- Loki's default OTLP config makes `service.name` and `deployment.environment.name` indexed labels; every other attribute becomes structured metadata.
- Queries filter on the plain `event_name` attribute rather than the OpenTelemetry event name, which Loki 3.7 and older drop.

## Import

1. In Grafana, open **Dashboards → New → Import**.
2. Upload `build-retry-events.json`.
3. Pick your Loki data source in the **Loki** variable at the top of the dashboard.

The dashboard's variables:

| Variable | Filters on |
|---|---|
| **Service namespace** | `service.namespace`, `jenkins` unless **Events resource attributes** replaces it. It must not be empty. |
| **Service** | `service.name`, the plugin's **Events service name** setting. |
| **Environment** | `deployment.environment.name`, from **Events resource attributes**. **All** also shows events sent without it. |
| **Controller URL** | `jenkins_url`, as a regular expression. |
| **Job** | The job's full name, as a regular expression. |

## Changing it

Edit the dashboard in Grafana, then copy its JSON model (**Dashboard settings → JSON Model**) over `build-retry-events.json`. Before committing, set the `loki` variable's `current` back to `{}`, so the file names no data source.
