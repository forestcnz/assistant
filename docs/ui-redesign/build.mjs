import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const [css, js, brand] = await Promise.all([
  readFile(path.join(here, 'prototype.css'), 'utf8'),
  readFile(path.join(here, 'prototype.js'), 'utf8'),
  readFile(path.join(here, '../branding/brand-mark.svg')),
]);
const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light">
<meta name="description" content="雕雕助手 Android APP 全页面 UI 设计稿，含可交互原型、页面状态与设计规范。">
<title>雕雕助手 · 全页面 UI 设计稿</title>
<style>${css}</style>
</head>
<body>
<div id="app"></div>
<noscript>请启用 JavaScript 以查看交互设计稿。</noscript>
<script>const BRAND_DATA = "data:image/svg+xml;base64,${brand.toString('base64')}";</script>
<script>${js.replace(/<\/script/gi, '<\\/script')}</script>
</body>
</html>`;
const target = path.resolve(here, '../app-ui-redesign.html');
await writeFile(target, html, 'utf8');
console.log(`Created ${target} (${Buffer.byteLength(html)} bytes). No runtime dependencies.`);
