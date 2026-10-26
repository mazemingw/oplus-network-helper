import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';

// Run the actual packaged shell functions with mocked Android commands/signals.
// No real process is signalled and no modem is accessed.
const asset = new URL('../app/src/main/assets/qmi/recover-bandmenu-session.sh', import.meta.url);
const source = await readFile(asset, 'utf8');
const entry = '\nbandmenu_recover "$@"';
if (!source.trimEnd().endsWith(entry)) throw new Error('Recovery entry point changed');
const library = source.trimEnd().slice(0, -entry.length);
const shell = process.argv[2] ?? (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'sh');
let passed = 0;
function run(name, body) {
    const result = spawnSync(shell, ['-s'], { input: library + '\n' + body, encoding: 'utf8' });
    if (result.error || result.status !== 0) throw new Error(`${name}: ${result.error ?? result.stderr + result.stdout}`);
    passed++;
}
const canonical = '/data/user/0/dev.qcom.bandmenu/files/qcom-bandlockd -uid 10599 -tokenfile /data/user/0/dev.qcom.bandmenu/files/auth_token ';
const quote = s => "'" + s.replaceAll("'", "'\"'\"'") + "'";
run('cmdline reader uses one bounded block and decodes NUL arguments', `
dd() {
    test "$*" = 'if=/proc/123/cmdline bs=4096 count=1' || return 1
    printf '/data/user/0/dev.qcom.bandmenu/files/qcom-bandlockd\\000-uid\\00010599\\000-tokenfile\\000/data/user/0/dev.qcom.bandmenu/files/auth_token\\000'
}
test "$(bandmenu_read_cmdline 123)" = ${quote(canonical)}
`);
run('cmdline disappearing during bounded read produces no identity', `
dd() { return 1; }
test -z "$(bandmenu_read_cmdline 123)"
`);
for (const [name, value, owner, user, matches] of [
    ['canonical identity', canonical, '10599', '0', true],
    ['legacy data path', canonical.replaceAll('/data/user/0/', '/data/data/'), '10599', '0', true],
    ['wrong owner', canonical.replace('10599', '10431'), '10599', '0', false],
    ['other package', canonical.replaceAll('dev.qcom.bandmenu', 'another.app'), '10599', '0', false],
    ['different token path', canonical.replace('auth_token', 'unrelated_token'), '10599', '0', false],
    ['shell command mentioning daemon', 'sh -c ' + canonical, '10599', '0', false],
    ['extra arguments', canonical + '-verbose ', '10599', '0', false],
    ['other Android user', canonical, '1010599', '10', false],
    ['matching Android user', canonical.replaceAll('/data/user/0/', '/data/user/10/').replace('10599', '1010599'), '1010599', '10', true]
]) run(name, `bandmenu_matches ${quote(value)} ${quote(owner)} ${quote(user)}\ntest $? -eq ${matches ? 0 : 1}\n`);

const mocks = `
test_am=0; test_kill=0; test_exited=0
id() { echo 0; }
cmd() { echo 'package:dev.qcom.bandmenu uid:10599'; }
ps() { printf '%s qcom-bandlockd\\n' "$$"; }
tr() { if [ "$test_exited" = 0 ]; then printf '%s' ${quote(canonical)}; fi; }
am() { test_am=$((test_am + 1)); }
kill() { test_kill=$((test_kill + 1)); test_exited=1; }
sleep() { :; }
`;
run('verified daemon is stopped, rechecked and released', mocks + `
bandmenu_recover 0 || exit 1
test "$test_am" = 1 && test "$test_kill" = 1
`);
run('no matching daemon leaves other applications alone', mocks + `
tr() { printf '%s' 'unrelated process '; }
bandmenu_recover 0 || exit 1
test "$test_am" = 0 && test "$test_kill" = 0
`);
run('PID identity change after force-stop is never signalled', mocks + `
am() { test_am=1; test_exited=1; }
bandmenu_recover 0 || exit 1
test "$test_am" = 1 && test "$test_kill" = 0
`);
run('force-stop denial does not kill a live application daemon', mocks + `
am() { return 1; }
bandmenu_recover 0
test $? = 16 && test "$test_kill" = 0
`);
run('persistent process exit is bounded', mocks + `
kill() { test_kill=$((test_kill + 1)); }
bandmenu_recover 0
test $? = 18 && test "$test_kill" = 1
`);
run('missing package never stops another application', mocks + `
cmd() { :; }
bandmenu_recover 0 || exit 1
test "$test_am" = 0 && test "$test_kill" = 0
`);
run('package UID must belong to the requested Android user', mocks + `
bandmenu_recover 10
test $? = 14 && test "$test_am" = 0 && test "$test_kill" = 0
`);
console.log(`${passed} shell recovery scenarios passed (mock Android commands and signals).`);
