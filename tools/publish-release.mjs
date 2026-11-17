import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { publishRelease } from './public-release.mjs';
const root = fileURLToPath(new URL('../', import.meta.url));
const index = process.argv.indexOf('--manifest');
if (index < 0) throw Error('Supply --manifest release.json [--execute]');
console.log(JSON.stringify(await publishRelease(root, resolve(process.argv[index + 1]), process.argv.includes('--execute')), null, 2));
