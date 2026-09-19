Lists what is in a directory: its subdirectories first, then its files, each marked as one or the
other and named relative to the directory you asked about.

`depth` is how far down to go — 1, the default, is the immediate children — and `limit` caps how
many entries come back, 50 by default. The reply says so when the limit was reached, which is the
signal to ask again for a narrower path rather than to assume you have seen everything.

The listing is confined to the home directories this conversation reaches — your own, and the
group's and the tenant's where it has them, all of which are named in your instructions. Omitting
`path` lists your own home. A path outside those directories is refused rather than listed.

`.git`, `node_modules`, `target`, `build`, `dist`, `.idea`, `.vscode` and `__pycache__` are skipped.

Use this to find your way around a directory you have not seen before — which skills are installed,
what a skill's folder holds, what has been written to the workspace. Use Glob when you know the name
you are after, since a listing of one level at a time is the slow way to find it.
