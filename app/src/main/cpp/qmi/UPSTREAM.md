# Upstream synchronization

- Upstream: https://github.com/Fronsipswu/qcom-band-menu
- Native helper and NAS layout source base: `bff702b5b4916f68feb3bf9308b91be56846f2e6`.
- Last reviewed upstream main: `d199690ec5a052553302ee4b4b460d681cbab1eb` (2026-10-26).

The four intervening upstream commits change Android socket setup, NR-mode
compatibility, partial-success presentation and the upstream application version.
They do not change the native helper or NAS message layouts. The existing
modified `nh-qmi.c` and its corresponding executable therefore remain unchanged.

Network Helper ports the NR-mode behavior in `QmiBandExecution.kt`,
`QmiProtocol.kt`, `QmiModemClient.kt` and `QmiBandLockViewModel.kt`:

- Send NR mode only when it differs from the reported mode.
- On an explicit unsupported/internal mode rejection, continue subsequent band/RAT
  commands, re-read the state, disable mode editing for that SIM and show a yellow
  partial-success alert. All attempted band/RAT changes still require readback.
- Resource/client exhaustion (including QMI 5/7) and transport failures stay fatal;
  writes are not replayed. Cell + RAT coupling remains strict because failure to
  select SA must not be reported as a successful NR-only cell lock.

Upstream's socket `connectto` allow rule is already covered by Network Helper's
existing targeted SELinux rule using the actual app/helper domains. Network Helper
keeps its UID/token authentication and does not adopt a permissive fallback.
The upstream DIAG UI/helper is outside this integration's scope.

Original copyright notices and GPL-3.0 terms remain applicable. See
`LICENSE-GPL-3.0`, `THIRD_PARTY_NOTICES.md` and `docs/qmi-controls.md`.
