# Security policy

## Reporting a vulnerability

Do not report security vulnerabilities in public GitHub issues or pull requests.

Report them privately through GitHub private vulnerability reporting: on the [Security tab](https://github.com/audiocodes/pipeline-build-retry-plugin/security) of the repository, choose **Report a vulnerability**.

## Supported versions

Fixes, when made, go into the latest release, with no commitment to a timeline; see [Support](README.md#support). Update to the latest version before you report a problem, and check whether it still happens there.

## Security notes for administrators

- The plugin queues new builds of a job. It never runs code of its own on agents, and it reads only the build's own flow graph and log and, with the Kubernetes plugin, why the build's agents ended.
- The optional monitoring events (README §7 "Events") send decision data to an endpoint the administrator configures. The token is a Jenkins credential, and parameter values and log text are never sent.
- Every web method needs `POST` and the matching permission.
