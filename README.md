# Screenshots for the relayed-401 diagnosis (sorsama/deepseek-harness-mobile#40)

Both captured on a Samsung SM-S731B against a relay built from the npm
`dsh-relay@0.2.1` tarball — the artifact that produced the report — reached at
`https://192.168.0.85:3553`, with a harness behind it.

- `relay-401-before-0.11.7.png` — DSH Mobile 0.11.7: "The relay rejected this device", inviting a re-pair that cannot help.
- `relay-401-after-patched.png` — the same attempt with this branch's build: the relay's missing harness session is named.

This branch exists only to host the two images; the change itself is on
`fix/relay-401-is-not-the-device-token`.
