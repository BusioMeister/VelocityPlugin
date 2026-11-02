# Standalone build inputs

This directory snapshots the shared source module and parent POM used by the synchronized
Paper/Velocity rewrite. `build.ps1` / `build.sh` install them before building the plugin.
The parent is installed with `-N`; its workspace module list is not traversed.

`source-manifest.json` records SHA-256 of these publication inputs **after CRLF-to-LF
normalization**. This makes checksums independent of Git checkout line endings.
Update shared inputs in both repositories together. These sources are shaded into the
plugin JAR; no separate shared plugin is installed on the server.
