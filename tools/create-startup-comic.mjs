#!/usr/bin/env node
// 绘制可编辑 SVG，并用本机 Chrome 导出启动漫画和应用图标使用的 PNG。
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';

const root = fileURLToPath(new URL('../', import.meta.url));
const svgPath = path.join(root, 'docs/branding/startup-comic.svg');
const pngPath = path.join(root, 'app/src/main/res/drawable-nodpi/startup_comic.png');
const brandSvgPath = path.join(root, 'docs/branding/brand-mark.svg');
const brandPngPath = path.join(root, 'app/src/main/res/drawable-nodpi/ic_brand_mark.png');
const brandOnly = process.argv.includes('--brand-only');
const ink = '#49352E';
const stroke = `stroke="${ink}" stroke-width="5" stroke-linejoin="round" stroke-linecap="round"`;
const p = (d, fill, extra = '') => {
  let defaults = stroke;
  if (extra.includes('stroke-width=')) defaults = defaults.replace('stroke-width="5"', '');
  if (extra.includes('stroke=')) defaults = defaults.replace(`stroke="${ink}"`, '');
  return `<path d="${d}" fill="${fill}" ${defaults} ${extra}/>`;
};
const line = (d, color = ink, width = 4) => `<path d="${d}" fill="none" stroke="${color}" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round"/>`;
const circle = (x, y, r, fill, extra = '') => `<circle cx="${x}" cy="${y}" r="${r}" fill="${fill}" ${extra}/>`;
const formatSvg = text => text.split('\n').map(line => line.trimEnd()).join('\n').trimEnd() + '\n';

function eyes(expression) {
  const whites = '<ellipse cx="103" cy="137" rx="24" ry="18" fill="#FFFDF7"/><ellipse cx="207" cy="137" rx="24" ry="18" fill="#FFFDF7"/>';
  if (expression === 'happy') return whites + line('M82 142 Q103 119 124 142 M186 142 Q207 119 228 142', ink, 5);
  if (expression === 'sleepy') return whites + line('M81 136 Q103 149 125 136 M185 136 Q207 149 229 136', ink, 5);
  const look = expression === 'worried' ? -2 : 4;
  return whites + [103, 207].map(x => circle(x + look, 139, 12, '#C58A38') + circle(x + look + 1, 140, 7, ink) + circle(x + look - 3, 135, 3, '#FFFDF7')).join('') +
    (expression === 'worried' ? line('M77 112 L125 101 M185 101 L233 112', '#754B32', 8) : line('M78 119 L128 126 M182 126 L232 119', '#754B32', 8));
}

function bird(x, y, scale, expression = 'tired', pose = 'default', shadow = true) {
  const left = pose === 'point' || pose === 'gaming' ? '' :
    pose === 'fold' ? p('M39 163 Q18 172 35 211 Q61 242 151 218 Q164 204 145 196 L80 188 Q64 157 39 163Z', '#96613E') :
    p('M41 155 Q5 159 14 209 L25 230 L36 218 L38 243 L53 229 L65 235 Q83 206 72 177Z', '#96613E') + line('M29 187 Q32 208 43 217 M45 180 Q47 201 58 210', '#C58A57', 3);
  const right = pose === 'gaming' ? '' :
    pose === 'fold' ? p('M272 163 Q296 174 280 209 Q251 237 171 219 Q158 205 177 195 L239 188 Q247 158 272 163Z', '#A77349') :
    p('M269 156 Q307 158 302 207 L291 231 L279 220 L276 242 L260 227 L249 233 Q231 202 244 178Z', '#A77349') + line('M286 183 Q282 205 272 216 M269 179 Q264 199 255 209', '#D09B64', 3);
  return `<g transform="translate(${x} ${y}) scale(${scale})">
    ${shadow ? '<ellipse cx="156" cy="301" rx="126" ry="10" fill="#76513C" opacity=".12"/>' : ''}
    ${p('M88 273 Q69 274 57 292 Q47 309 66 307 L80 300 Q76 316 91 309 L105 299 Q120 310 128 299 L123 278Z', '#E9B74E')}
    ${p('M213 276 Q227 273 243 288 Q261 301 248 307 L233 299 Q240 315 226 309 L214 300 Q199 309 190 298 L193 281Z', '#E9B74E')}
    ${line('M60 303 L68 298 M83 309 L91 300 M242 304 L235 297 M226 309 L221 300', '#6B4A35', 4)}
    ${p('M33 137 C19 112 33 88 53 77 L43 61 L76 68 L83 45 L108 57 L126 35 L148 54 L170 39 L190 60 L218 48 L219 74 L249 69 L243 91 C268 100 285 125 280 151 C304 177 313 206 298 236 L306 247 L283 248 C271 279 234 290 202 290 L114 290 C76 293 43 279 28 254 L14 253 L22 237 C5 199 9 165 33 137Z', 'url(#feathers)')}
    <ellipse cx="157" cy="218" rx="105" ry="69" fill="url(#belly)"/>
    ${p('M48 111 Q73 86 102 100 Q129 101 153 120 Q180 100 208 100 Q241 90 263 115 L251 128 L266 145 L250 149 L260 164 L236 168 L238 181 L215 177 Q187 191 157 175 Q127 191 100 177 L76 182 L79 169 L54 169 L62 153 L44 145 L58 132Z', '#F7E8C9', 'stroke-width="2.5"')}
    ${line('M93 209 Q104 218 112 209 M129 235 Q141 244 151 233 M174 213 Q186 223 195 212 M204 247 Q216 256 225 244 M86 251 Q96 259 105 251 M144 270 Q156 279 167 269', '#DDC399', 2.5)}
    ${left}${right}
    <ellipse cx="69" cy="166" rx="15" ry="8" fill="#D68C72" opacity=".48"/>
    <ellipse cx="242" cy="166" rx="15" ry="8" fill="#D68C72" opacity=".48"/>
    ${eyes(expression)}
    <rect x="57" y="110" width="90" height="55" rx="16" fill="none" stroke="#332E2A" stroke-width="7"/>
    <rect x="163" y="110" width="90" height="55" rx="16" fill="none" stroke="#332E2A" stroke-width="7"/>
    ${line('M147 132 Q155 127 163 132 M57 122 L43 116 M253 122 L267 116', '#332E2A', 6)}
    ${p('M143 145 Q154 133 168 144 L182 159 Q185 178 162 193 Q168 176 154 170 L138 163 Q131 156 143 145Z', 'url(#beak)', 'stroke-width="4"')}
    ${line('M146 159 Q158 165 175 160', '#93632E', 2.5)}
    ${expression === 'sleepy' ? '<ellipse cx="159" cy="174" rx="7" ry="10" fill="#69432E"/>' : ''}
    ${line('M76 83 L91 80 M107 72 L118 75 M185 76 L201 73 M230 94 L241 96', '#CB9864', 3)}
  </g>`;
}

function phone(x, y, width, mode = 'play') {
  const h = width * 1.65;
  const screen = `<rect x="0" y="0" width="${width}" height="${h}" rx="19" fill="#E9E5D9" ${stroke}/><rect x="8" y="10" width="${width - 16}" height="${h - 20}" rx="12" fill="#FFFDF6"/><path d="M${width * .38} 15 H${width * .62}" stroke="${ink}" stroke-width="5" stroke-linecap="round"/>`;
  let contents = '';
  if (mode === 'busy') {
    contents = [0, 1, 2, 3].map(i => `<rect x="17" y="${39 + i * 27}" width="13" height="13" rx="3" fill="${i % 2 ? '#F5C976' : '#F1C2AD'}"/>${line(`M39 ${44 + i * 27} H${width - 17}`, '#B3AAA0', 4)}`).join('');
  } else if (mode === 'play') {
    contents = circle(width / 2, h / 2 + 4, width * .29, '#4C9B83') + `<path d="M${width * .43} ${h / 2 - 11} L${width * .65} ${h / 2 + 4} L${width * .43} ${h / 2 + 19}Z" fill="#FFFDF6"/>`;
  } else if (mode === 'checks') {
    contents = [0, 1, 2].map(i => `<rect x="15" y="${32 + i * 40}" width="${width - 30}" height="30" rx="10" fill="#E1F0D9"/>${line(`M22 ${48 + i * 40} L28 ${54 + i * 40} L40 ${40 + i * 40}`, '#499573', 4)}${line(`M50 ${47 + i * 40} H${width - 22}`, '#9FBAA0', 4)}`).join('');
  } else {
    contents = circle(width / 2, h / 2 + 3, width * .29, '#4C9B83') + line(`M${width * .33} ${h / 2 + 3} L${width * .47} ${h / 2 + 15} L${width * .70} ${h / 2 - 10}`, '#FFFDF6', 5);
  }
  return `<g transform="translate(${x} ${y})">${screen}${contents}${line(`M${width * .40} ${h - 9} H${width * .60}`, '#B6ADA0', 3)}</g>`;
}

function caption(text, color) {
  return `${p('M48 25 H291 Q311 25 311 45 V68 Q311 86 292 86 H119 L100 101 L95 86 H48 Q29 86 29 67 V44 Q29 25 48 25Z', color, 'stroke-width="3.5"')}<text x="51" y="66" font-size="34" font-weight="700" fill="${ink}">${text}</text>`;
}
function spark(x, y, color = '#D09D4C', scale = 1) {
  return `<g transform="translate(${x} ${y}) scale(${scale})">${p('M0 -12 Q2 -2 12 0 Q2 2 0 12 Q-2 2 -12 0 Q-2 -2 0 -12Z', color, 'stroke="none"')}</g>`;
}
function panel(x, y, background, content) {
  return `<g transform="translate(${x} ${y})"><rect x="0" y="7" width="472" height="472" rx="28" fill="#DCCBBB" opacity=".5"/><rect width="472" height="472" rx="28" fill="${background}" ${stroke}/><g clip-path="url(#panelClip)">${content}</g></g>`;
}

const first = caption('任务好多…', '#FFE0A8') + `
  <path d="M27 356 H445" stroke="#ECD9C5" stroke-width="3"/>
  <g transform="translate(38 110) rotate(-12)">${p('M0 0 H45 V55 H0Z', '#FFFDF6', 'stroke-width="3"')}${line('M10 14 H34 M10 25 H34 M10 36 H27', '#C5B7A6', 3)}</g>
  <g transform="translate(372 105) rotate(12)">${p('M0 0 H45 V55 H0Z', '#FFFDF6', 'stroke-width="3"')}${line('M10 14 H34 M10 25 H34 M10 36 H27', '#C5B7A6', 3)}</g>
  ${bird(21, 126, .98, 'worried')}
  ${phone(327, 190, 104, 'busy')}
  ${p('M328 247 Q346 234 355 245 L348 261 Q335 272 325 262Z', '#A77349', 'stroke-width="4"')}
  ${p('M286 132 Q307 154 291 164 Q275 166 280 150Z', '#87BECD', 'stroke-width="3"')}
  ${line('M313 116 L319 105 M330 132 L345 126 M312 171 L325 180', '#B88761', 3)}
  <rect x="19" y="415" width="434" height="23" rx="9" fill="#C99569" stroke="${ink}" stroke-width="4"/>
  ${line('M46 438 V473 M425 438 V473', '#835A40', 9)}
`;
const second = caption('点一下！', '#F9E8AD') + `
  <ellipse cx="287" cy="431" rx="148" ry="11" fill="#E3DFC6"/>
  ${phone(42, 199, 114, 'play')}
  ${bird(165, 137, .91, 'bright', 'point')}
  ${p('M231 320 Q189 331 148 308 L119 285 Q105 272 116 260 Q127 250 140 267 L164 284 L208 281 Q235 279 248 299Z', '#A77349', 'stroke-width="4.5"')}
  ${line('M106 253 L94 244 M97 277 L82 278 M133 248 L136 234', '#D0A74F', 4)}
  ${circle(363, 121, 24, '#F7D45F', stroke)}
  ${p('M348 139 L353 155 H373 L378 139', '#E1C783', 'stroke-width="3"')}
  ${line('M352 160 H373 M363 82 V69 M396 98 L408 90 M329 98 L318 89 M405 125 H418', '#D1A348', 4)}
  ${spark(425, 185, '#E4C36A', .8)}
`;
const third = caption('自动搞定！', '#D5EBD8') + `
  <ellipse cx="163" cy="430" rx="142" ry="11" fill="#D9E3CE"/>
  ${bird(21, 143, .92, 'sleepy', 'fold')}
  ${phone(325, 188, 104, 'checks')}
  ${line('M308 198 L293 189 M309 226 H289 M309 252 L294 261 M444 218 L457 214 M443 246 L455 254', '#86AB89', 4)}
  ${spark(346, 142, '#86AC75', 1.15)}
  ${spark(428, 147, '#D1BD71', .7)}
  ${line('M263 137 Q272 123 282 137 Q292 149 303 135', '#B9C6AD', 3)}
  <ellipse cx="289" cy="155" rx="5" ry="8" fill="#B9C6AD"/>
`;
const fourth = caption('继续躺平～', '#F3D8CA') + `
  ${p('M44 229 Q44 192 83 192 H390 Q430 192 430 231 V390 H44Z', '#DBAF99', 'stroke-width="4"')}
  <rect x="41" y="311" width="392" height="111" rx="34" fill="#E9C2A8" stroke="${ink}" stroke-width="4"/>
  <rect x="21" y="294" width="60" height="128" rx="27" fill="#C9927B" stroke="${ink}" stroke-width="4"/>
  <rect x="391" y="294" width="60" height="128" rx="27" fill="#C9927B" stroke="${ink}" stroke-width="4"/>
  ${line('M66 422 L62 447 M409 422 L414 447', '#6E4E3E', 8)}
  ${phone(375, 139, 62, 'done')}
  ${bird(76, 130, .98, 'happy', 'gaming')}
  ${p('M115 293 Q86 304 93 342 Q105 362 142 352 L149 330 L135 309Z', '#96613E', 'stroke-width="4"')}
  <g transform="translate(95 291) rotate(-8 25 41)">
    <rect width="51" height="84" rx="9" fill="#CE5E4D" stroke="${ink}" stroke-width="4"/>
    <path d="M4 15 Q31 24 47 7 V23 Q20 31 6 60 L4 48Z" fill="#FFF3DE"/>
    <ellipse cx="25.5" cy="4" rx="24" ry="7" fill="#DBCFC0" stroke="${ink}" stroke-width="3"/>
    ${line('M22 2 L32 0', ink, 3)}
    <path d="M10 75 H42" stroke="#A23F37" stroke-width="4" stroke-linecap="round"/>
  </g>
  ${p('M303 292 Q335 291 352 318 Q367 350 346 366 L311 353 L295 323Z', '#A77349', 'stroke-width="4"')}
  <g transform="translate(241 321) rotate(8 59 28)">
    ${p('M23 2 Q10 0 6 17 L0 44 Q-3 59 10 60 Q17 60 29 46 H89 Q101 60 110 60 Q123 58 118 43 L111 17 Q108 0 95 2 L78 8 H40Z', '#62777B', 'stroke-width="4"')}
    ${line('M25 19 V37 M16 28 H34', '#E8E5D6', 5)}
    ${circle(92, 21, 4, '#DE9C88')}${circle(102, 30, 4, '#EBD27F')}${circle(83, 30, 4, '#D3DEB3')}${circle(92, 39, 4, '#B2CAD2')}
    <rect x="50" y="13" width="20" height="7" rx="3" fill="#E0E8DF"/>
  </g>
  ${spark(405, 115, '#D4AE62', .9)}${spark(56, 161, '#D4AE62', .8)}
`;
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024" role="img" aria-labelledby="comic-title comic-desc">
<title id="comic-title">肥宅雕四格漫画</title>
<desc id="comic-desc">任务太多，点一下开始，助手自动完成，肥宅雕继续抱着可乐打游戏。</desc>
<defs>
  <linearGradient id="feathers" x1="0" y1="0" x2=".7" y2="1"><stop stop-color="#BD8955"/><stop offset="1" stop-color="#8B5C3D"/></linearGradient>
  <linearGradient id="belly" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#FFF3D9"/><stop offset="1" stop-color="#EBD4AE"/></linearGradient>
  <linearGradient id="beak" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#FFD46B"/><stop offset="1" stop-color="#D8A33B"/></linearGradient>
  <clipPath id="panelClip"><rect x="3" y="3" width="466" height="466" rx="25"/></clipPath>
</defs>
<rect width="1024" height="1024" fill="#FBF4E9"/>
<g font-family="'Microsoft YaHei','Noto Sans CJK SC',sans-serif">
${panel(28, 24, '#FFF1E3', first)}
${panel(524, 24, '#FCF8DE', second)}
${panel(28, 524, '#F0F4E5', third)}
${panel(524, 524, '#FCEDDF', fourth)}
</g></svg>`;
await fs.mkdir(path.dirname(svgPath), { recursive: true });
if (!brandOnly) await fs.writeFile(svgPath, formatSvg(svg), 'utf8');
const brandDefs = svg.slice(svg.indexOf('<defs>'), svg.indexOf('</defs>') + '</defs>'.length);
const brandSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="-14 14 340 340" role="img" aria-labelledby="brand-title brand-desc">
<title id="brand-title">雕雕助手 SVG 标志</title>
<desc id="brand-desc">戴方框眼镜、交叠双翅的圆滚滚肥宅雕；透明背景。</desc>
${brandDefs}
${bird(0, 0, 1, 'tired', 'fold', false)}
</svg>`;
await fs.writeFile(brandSvgPath, formatSvg(brandSvg), 'utf8');
const assets = [
  ...(!brandOnly ? [{ svgPath, pngPath, size: 1024 }] : []),
  { svgPath: brandSvgPath, pngPath: brandPngPath, size: 512 }
];

function cdp(url) {
  const socket = new WebSocket(url);
  const pending = new Map();
  const listeners = new Set();
  let nextId = 1;
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.id) {
      const request = pending.get(message.id);
      if (!request) return;
      pending.delete(message.id);
      message.error ? request.reject(new Error(message.error.message)) : request.resolve(message.result);
    } else {
      for (const listener of listeners) listener(message);
    }
  });
  socket.addEventListener('close', () => {
    for (const request of pending.values()) request.reject(new Error('Chrome connection closed'));
    pending.clear();
  });
  return {
    ready: new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, { once: true });
      socket.addEventListener('error', reject, { once: true });
    }),
    send(method, params = {}, sessionId) {
      return new Promise((resolve, reject) => {
        const id = nextId++;
        pending.set(id, { resolve, reject });
        socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
      });
    },
    event(method, sessionId) {
      return new Promise(resolve => {
        const listener = message => {
          if (message.method === method && message.sessionId === sessionId) {
            listeners.delete(listener);
            resolve(message.params);
          }
        };
        listeners.add(listener);
      });
    },
    close() { socket.close(); }
  };
}

const chromePath = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const profilePath = path.join(root, 'build/branding/chrome-profile');
await fs.mkdir(profilePath, { recursive: true });
const chrome = spawn(chromePath, [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--remote-debugging-port=0', `--user-data-dir=${profilePath}`, 'about:blank'
], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
let connection;
const renderTimeout = setTimeout(() => { chrome.kill(); }, 30_000);
try {
  const endpoint = await new Promise((resolve, reject) => {
    let stderr = '';
    chrome.on('error', reject);
    chrome.on('exit', code => reject(new Error(`Chrome exited before rendering: ${code}`)));
    chrome.stderr.on('data', chunk => {
      stderr += chunk.toString();
      const match = stderr.match(/DevTools listening on (ws:\/\/[^\s]+)/);
      if (match) resolve(match[1]);
    });
  });
  connection = cdp(endpoint);
  await connection.ready;
  const { targetId } = await connection.send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await connection.send('Target.attachToTarget', { targetId, flatten: true });
  const page = (method, params = {}) => connection.send(method, params, sessionId);
  await page('Page.enable');
  await page('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } });
  for (const asset of assets) {
    const { size } = asset;
    await page('Emulation.setDeviceMetricsOverride', { width: size, height: size, deviceScaleFactor: 1, mobile: false });
    const loaded = connection.event('Page.loadEventFired', sessionId);
    const navigation = await page('Page.navigate', { url: pathToFileURL(asset.svgPath).href });
    if (navigation.errorText) throw new Error(navigation.errorText);
    await loaded;
    const documentCheck = await page('Runtime.evaluate', {
      expression: "document.documentElement.localName === 'svg' && !document.querySelector('parsererror')",
      returnByValue: true
    });
    if (documentCheck.result.value !== true) throw new Error('SVG XML parsing failed');
    await page('Runtime.evaluate', { expression: 'document.fonts.ready.then(() => true)', awaitPromise: true });
    const screenshot = await page('Page.captureScreenshot', {
      format: 'png', captureBeyondViewport: false,
      clip: { x: 0, y: 0, width: size, height: size, scale: 1 }
    });
    await fs.mkdir(path.dirname(asset.pngPath), { recursive: true });
    await fs.writeFile(asset.pngPath, Buffer.from(screenshot.data, 'base64'));
    console.log(JSON.stringify({ svg: asset.svgPath, png: asset.pngPath, size: `${size}x${size}` }, null, 2));
  }
} finally {
  clearTimeout(renderTimeout);
  if (connection) {
    await connection.send('Browser.close').catch(() => {});
    connection.close();
  }
  chrome.kill();
}
