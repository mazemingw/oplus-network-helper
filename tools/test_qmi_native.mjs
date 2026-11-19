import { readFile } from 'node:fs/promises';

const { instance } = await WebAssembly.instantiate(await readFile(process.argv[2]), {});
const failure = instance.exports.nh_test_run();
if (failure !== 0) throw new Error(`NAS session test ${failure} failed`);
console.log('20 native NAS session, NR multi-PCI and monitoring scenarios passed (mock QRTR).');
