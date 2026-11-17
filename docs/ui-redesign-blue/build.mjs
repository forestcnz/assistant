import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const [baseCss, baseJs, brand, overlayCss, overlayJs] = await Promise.all([
  readFile(path.join(here, 'prototype.css'), 'utf8'),
  readFile(path.join(here, 'prototype.js'), 'utf8'),
  readFile(path.join(here, '../branding/brand-mark.svg')),
  readFile(path.join(here, 'compact-overlays.css'), 'utf8'),
  readFile(path.join(here, 'compact-overlays.js'), 'utf8'),
]);
const css = baseCss + '\n' + overlayCss;
const bootstrap = 'const hash=location.hash.slice(1);';
if (!baseJs.includes(bootstrap)) throw new Error('Prototype bootstrap marker is missing.');
const js = baseJs.replace(bootstrap, () => overlayJs + '\n' + bootstrap);
const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light">
<meta name="description" content="雕雕助手 Android APP 全页面 UI 设计稿，含可交互原型、页面状态与设计规范。">
<title>雕雕助手 · 雾蓝暖白 UI 设计稿</title>
<style>${css}</style>
</head>
<body>
<div id="app"></div>
<noscript>请启用 JavaScript 以查看交互设计稿。</noscript>
<script>const BRAND_DATA = "data:image/svg+xml;base64,${brand.toString('base64')}";</script>
<script>${js.replace(/<\/script/gi, '<\\/script')}</script>
</body>
</html>`;
const target = path.resolve(here, '../app-ui-redesign-blue.html');
await writeFile(target, html, 'utf8');
console.log(`Created ${target} (${Buffer.byteLength(html)} bytes). No runtime dependencies.`);
