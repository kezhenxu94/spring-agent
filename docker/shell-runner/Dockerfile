# The sandbox the agent's Bash tool runs commands in — one container per user, on Kubernetes as a
# Pod and on Docker as a container. Not the application image: that one is built by the Paketo
# buildpack from spring-agent-app, and nothing here runs Java.
#
# The two shells drive this image through scripts they write, so the floor is not negotiable:
#
#   bash            the watchdog runs under `sh -c`, every command under `bash -c`
#   coreutils       `stat -c` for the idle watchdog and the background-shell read offsets, and
#                   `dd` for the byte range BashOutput hands back
#   util-linux      `setsid`, which detaches a background shell from the exec that started it
#
# All three are essential packages on Debian, so they are inherited rather than installed. That is
# the reason for this base: those scripts were written against GNU behaviour, and busybox's `stat`,
# `dd` and `setsid` are close enough to look right and differ in the details — a shell that
# silently reports the wrong byte offset is worse than one that is absent.

# Not Debian's `yq`, which is a Python wrapper that speaks jq's language over YAML. This is
# mikefarah's Go implementation, the one `yq '.a.b'` and `yq -i` refer to everywhere else. Taken
# from its own image rather than downloaded, so the build states a version instead of a URL and the
# architecture follows whatever this image is being built for.
FROM mikefarah/yq:4 AS yq

FROM debian:trixie-slim

LABEL org.opencontainers.image.source="https://github.com/kezhenxu94/spring-agent"
LABEL org.opencontainers.image.description="Sandbox runtime for the spring-agent shell tools"
LABEL org.opencontainers.image.licenses="Apache-2.0"

# Everything here is convenience — what an agent shell reaches for often enough that every
# deployment would otherwise add it. A deployment needing a language toolchain should build FROM
# this image rather than widen it for everyone.
#
# --no-install-recommends keeps this to what is named; without it python3 alone pulls in most of a
# desktop's worth of suggestions.
RUN apt-get update \
  && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    less \
    procps \
    unzip \
    git \
    gh \
    jq \
    python3 \
  && rm -rf /var/lib/apt/lists/*

COPY --from=yq /usr/bin/yq /usr/local/bin/yq

# Root, deliberately. The working directory is the user's own directory under `storage.location`,
# bind-mounted from the host on Docker and a PVC subPath on Kubernetes; both are created by the
# application process, so a container running as some other uid would find them unwritable. The
# isolation this image provides is between users, not between the agent and its own files.
ENV HOME=/root
WORKDIR /root

# Never actually used: both shells replace it with their own watchdog loop, which exits when the
# user goes idle or the sandbox reaches its hard deadline. Here so that `docker run` on this image
# does something unsurprising.
CMD ["sleep", "infinity"]
