# spring-agent-tools-shell-docker

> **Audience:** a developer working on the sandbox, and the operator deciding whether to turn a shell
> on at all. Selected with `app.ai.tools.shell.type=docker` (`TOOLS_SHELL_TYPE`); the property
> reference is the `app.ai.tools.shell.docker` block in
> [`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml).

The shell tools, running commands in **a container per user** on a Docker daemon this application can
reach. The single-host equivalent of
[the Kubernetes backend](../spring-agent-tools-shell-kubernetes/README.md), and like it a real sandbox
— unlike `local`, which runs the model's commands in the server process.

The shell defaults to `none` everywhere. Turn it on deliberately.

## What it contributes

| | |
| --- | --- |
| `DockerShellTools` | `Bash`, `BashOutput`, `KillShell`, `RestartShellContainer` |
| `UserContainerManager` | A container per user, torn down when idle and rebuilt on the next command |

`image`, `network`, `idleTimeout`, `hardDeadline` and `startupTimeout` are the whole of what a
deployment sets. Credentials are stored encrypted here rather than as Secrets, which is what
`TOOLS_SHELL_DOCKER_CREDENTIALS_ENCRYPTION_KEY` is for.

`DockerShellDefaultsTest` in `spring-agent-app-webui` binds these properties rather than parsing the
YAML, so it also catches a block landing at a nesting level Boot ignores in silence — which is the
check that keeps the applications' `application.yaml` files in step on this backend.


## Text the model reads

Both halves are this backend's own, not core's, because all three shell backends declare tools of
the same names — `Bash`, `BashOutput`, `KillShell` — and a name is what a translation is keyed by.
The descriptions live in `shell-docker/prompts/tools/` with `shell-docker/tools*.properties` for
their parameters, and what the tools *answer* with lives in `shell-docker/messages*.properties`,
read through core's `ModuleMessages`. Both bean definitions are conditional on this backend being
the active one, so no two of them are ever asked about `Bash` at once —
`LocalizingToolCallingManager` warns if that ever happens.

A shell result is what the model reasons from and then reports, so English there pulls the whole
turn into English however the user wrote. The identifiers stay as they are — `bash_id`, `STDERR`,
the tool names — since those are what the model has to type back rather than prose it reads.

## Native image

The backend is chosen during AOT, so in a native image it is a **build-time** decision baked by
`-PnativeBackends`. `TOOLS_SHELL_TYPE` is inert at runtime there.
