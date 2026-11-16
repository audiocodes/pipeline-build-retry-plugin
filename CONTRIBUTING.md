# Contributing to the Pipeline Build Retry plugin

Contributions are welcome: bug reports, ideas, documentation and code. This plugin follows the Jenkins project's [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md) and its [Code of Conduct](CODE_OF_CONDUCT.md). Report security vulnerabilities as [SECURITY.md](SECURITY.md) describes, never in a public issue.

## Reporting a bug or an idea

Open a [GitHub issue](https://github.com/audiocodes/pipeline-build-retry-plugin/issues). For a bug, include:

- the Jenkins version and the plugin version;
- the job's `buildRetry(…)` options;
- the build's `[Build Retry]` log lines (`verbose: true` adds the details behind each decision);
- what you expected and what happened.

## Building and testing

You need JDK 21 (CI also runs 25) and Maven.

```bash
mvn spotless:apply
mvn verify
```

- `mvn verify` runs everything: the tests (several minutes, most start a real Jenkins), Spotless, SpotBugs, the enforcer rules and Javadoc. It must end green, with SpotBugs at 0.
- Spotless formatting is enforced, so run `mvn spotless:apply` first.
- For a quick loop on one class: `mvn -Dtest=RetryDecisionTest -Dmaven.javadoc.skip=true verify`. Run a full `mvn verify` before you open a pull request.
- To try the plugin in a local Jenkins: `mvn hpi:run`.

The code layout, the design decisions and the rules for changing them are in [AGENTS.md](AGENTS.md).

## Pull requests

- Write the failing test first, then the change. Decision logic belongs in `core/`, which is plain Java and tested with plain JUnit.
- Keep each pull request to one change, and explain the problem it solves in its description.
- When the plugin's behaviour changes, update [README.md](README.md) and [docs/how-it-works.md](docs/how-it-works.md) in the same pull request.
- Every form field, Pipeline argument and step needs a help file; `HelpFilesTest` checks this.
- Use generic names in code, tests, docs and examples, with placeholders such as `CONTROLLER` and `ADMIN`. The only names are the copyright holder in `LICENSE.md` and file headers, the repository URLs, and the author credit.

By submitting a contribution, you agree that it is licensed under the [MIT License](LICENSE.md).
