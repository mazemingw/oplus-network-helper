# Upstream synchronization

- Upstream: https://github.com/Fronsipswu/qcom-band-menu
- Native helper and NAS layout source base: `bff702b5b4916f68feb3bf9308b91be56846f2e6`.
- Last reviewed upstream main: `e924f388b8c69dfbe7f91e76a7721698d8099e5a` (2026-11-06).

The upstream NAS helper and message layouts still use the source base above.
The newer native changes are for the standalone DIAG helper, including LTE PUSCH
v50 decoding. This app integrates the NAS helper and its own Compose UI.

Network Helper ports the NR-mode behavior in `QmiBandExecution.kt`,
`QmiProtocol.kt`, `QmiModemClient.kt` and `QmiBandLockViewModel.kt`:

- Send NR mode only when it differs from the reported mode.
- Send a changed RAT before the band masks, since some modems reset the masks
  on a RAT write. Restore known masks for the enabled RATs afterwards. With an unchanged RAT, only
  write masks that differ from the reported state.
- On an explicit unsupported/internal mode rejection, continue processing the plan,
  re-read the state, disable mode editing for that SIM and show a yellow
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
