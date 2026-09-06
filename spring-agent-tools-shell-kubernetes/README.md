# spring-agent-tools-shell-kubernetes

> **Audience:** a developer working on the sandbox, and the operator deciding whether to turn a shell
> on at all. Selected with `app.ai.tools.shell.type=kubernetes` (`TOOLS_SHELL_TYPE`); the property
> reference is the `app.ai.tools.shell.kubernetes` block in
> [`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml).

The shell tools, running commands in **a Pod per user**. This is the backend to prefer where a
deployment serves more than one person: `local` runs the model's commands in the server process, with
its filesystem and its secrets.

The shell defaults to `none` everywhere. Turn it on deliberately.

## What it contributes

| | |
| --- | --- |
| `KubernetesShellTools` | `Bash`, `BashOutput`, `KillShell`, `RestartShellPod` |
| `UserPodManager` | A Pod per user, with its own slice of the volume, torn down when idle and rebuilt on the next command |
| `KubernetesSecretCredentialStore` | Core's credential store, as Kubernetes Secrets mounted into that user's Pod |

`idleTimeout`, `hardDeadline` and `startupTimeout` bound a sandbox's life and a command's; `namespace`,
`image`, `workingDir`, `fsGroup`, image pull secrets and the storage PVC name say what the Pod is.

## Credentials never reach a prompt

A credential is a Secret mounted into the asking user's Pod, so a token reaches a shell as an
environment variable and is never in anything the model reads.

An operator can also share Secrets they provisioned themselves — with a group, a tenant, or one named
person — by labelling them to match a selector under
`app.ai.tools.shell.kubernetes.credentials.shared`. A credential the user set for themselves still
wins the name.


## Text the model reads

Both halves are this backend's own, not core's, because all three shell backends declare tools of
the same names — `Bash`, `BashOutput`, `KillShell` — and a name is what a translation is keyed by.
The descriptions live in `shell-kubernetes/prompts/tools/` with `shell-kubernetes/tools*.properties` for
their parameters, and what the tools *answer* with lives in `shell-kubernetes/messages*.properties`,
read through core's `ModuleMessages`. Both bean definitions are conditional on this backend being
the active one, so no two of them are ever asked about `Bash` at once —
`LocalizingToolCallingManager` warns if that ever happens.

A shell result is what the model reasons from and then reports, so English there pulls the whole
turn into English however the user wrote. The identifiers stay as they are — `bash_id`, `STDERR`,
the tool names — since those are what the model has to type back rather than prose it reads.

## Native image

The backend is chosen during AOT, so in a native image it is a **build-time** decision baked by
`-PnativeBackends` (see `springagent.native.gradle`). `TOOLS_SHELL_TYPE` is inert at runtime there and
must be set to agree with what was baked.
