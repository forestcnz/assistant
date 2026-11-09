import { build } from 'esbuild';
import { resolve } from 'node:path';
await build({
  entryPoints: [resolve(import.meta.dirname, 'hook.js')],
  outfile: resolve(process.argv[2]), bundle: true, format: 'iife', platform: 'neutral',
  target: 'es2020', minify: false, legalComments: 'inline',
});
