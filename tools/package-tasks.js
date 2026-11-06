#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const UTF8_FLAG = 0x0800;
const MAX_ENTRIES = 0xffff;
const MAX_ENTRY_SIZE = 0xffffffff;
const MAX_TOTAL_SIZE = 2 * 1024 * 1024 * 1024;

function usage() {
  console.log('Usage: node tools/package-tasks.js <tasks-dir> [output.zip]');
  console.log('Default output: build/tasks-bundle.zip');
}

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < table.length; n++) {
    let value = n;
    for (let bit = 0; bit < 8; bit++) {
      value = (value & 1) ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
    }
    table[n] = value >>> 0;
  }
  return table;
})();

function dosDateTime(date) {
  const year = Math.max(1980, date.getFullYear());
  const dosDate = ((year - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  const dosTime = (date.getHours() << 11) | (date.getMinutes() << 5) | Math.floor(date.getSeconds() / 2);
  return { dosDate, dosTime };
}

function assertSafeName(name) {
  if (!name || name === '.' || name === '..' || /[\\/\u0000-\u001f\u007f]/u.test(name)) {
    throw new Error(`Invalid task or file name: ${JSON.stringify(name)}`);
  }
}

function collectFiles(dir, archivePrefix) {
  const collected = [];
  const entries = fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
  for (const entry of entries) {
    assertSafeName(entry.name);
    const fullPath = path.join(dir, entry.name);
    const archivePath = `${archivePrefix}/${entry.name}`;
    if (entry.isSymbolicLink()) {
      throw new Error(`Symbolic links are not supported: ${fullPath}`);
    }
    if (entry.isDirectory()) {
      collected.push(...collectFiles(fullPath, archivePath));
    } else if (entry.isFile()) {
      collected.push({ fullPath, archivePath });
    }
  }
  return collected;
}

function taskDirectories(inputDir) {
  if (fs.existsSync(path.join(inputDir, 'task.js')) && fs.statSync(path.join(inputDir, 'task.js')).isFile()) {
    return [{ name: path.basename(inputDir), dir: inputDir }];
  }
  return fs.readdirSync(inputDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && !entry.isSymbolicLink())
    .map((entry) => ({ name: entry.name, dir: path.join(inputDir, entry.name) }))
    .filter((task) => fs.existsSync(path.join(task.dir, 'task.js')));
}

function makeArchive(files) {
  const localParts = [];
  const centralParts = [];
  let localOffset = 0;
  let totalSize = 0;

  for (const file of files) {
    const nameBytes = Buffer.from(file.archivePath.replaceAll(path.sep, '/'), 'utf8');
    if (nameBytes.length > 0xffff) throw new Error(`ZIP path is too long: ${file.archivePath}`);

    const source = fs.readFileSync(file.fullPath);
    totalSize += source.length;
    if (source.length > MAX_ENTRY_SIZE || totalSize > MAX_TOTAL_SIZE) {
      throw new Error('The uncompressed archive exceeds the APP import limit (2 GiB).');
    }
    const compressed = zlib.deflateRawSync(source, { level: 6 });
    if (compressed.length > MAX_ENTRY_SIZE) throw new Error(`File is too large for a standard ZIP: ${file.fullPath}`);

    const stat = fs.statSync(file.fullPath);
    const { dosDate, dosTime } = dosDateTime(stat.mtime);
    const checksum = crc32(source);

    const local = Buffer.alloc(30 + nameBytes.length);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(UTF8_FLAG, 6);
    local.writeUInt16LE(8, 8);
    local.writeUInt16LE(dosTime, 10);
    local.writeUInt16LE(dosDate, 12);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(source.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    local.writeUInt16LE(0, 28);
    nameBytes.copy(local, 30);
    localParts.push(local, compressed);

    const central = Buffer.alloc(46 + nameBytes.length);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(0x0314, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(UTF8_FLAG, 8);
    central.writeUInt16LE(8, 10);
    central.writeUInt16LE(dosTime, 12);
    central.writeUInt16LE(dosDate, 14);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(compressed.length, 20);
    central.writeUInt32LE(source.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt16LE(0, 34);
    central.writeUInt16LE(0, 36);
    central.writeUInt32LE(0, 38);
    central.writeUInt32LE(localOffset, 42);
    nameBytes.copy(central, 46);
    centralParts.push(central);

    localOffset += local.length + compressed.length;
  }

  if (files.length > MAX_ENTRIES) throw new Error('Too many files for a standard ZIP archive.');
  const centralDirectory = Buffer.concat(centralParts);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(centralDirectory.length, 12);
  end.writeUInt32LE(localOffset, 16);
  end.writeUInt16LE(0, 20);
  return Buffer.concat([...localParts, centralDirectory, end]);
}

function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help') || args.includes('-h')) {
    usage();
    return;
  }

  if (!args[0]) {
    usage();
    throw new Error('Please provide the directory containing the task folders.');
  }

  const cwd = process.cwd();
  const inputDir = path.resolve(cwd, args[0]);
  const outputZip = path.resolve(cwd, args[1] || path.join('build', 'tasks-bundle.zip'));
  if (!fs.statSync(inputDir, { throwIfNoEntry: false })?.isDirectory()) {
    throw new Error(`Tasks directory not found: ${inputDir}`);
  }

  const tasks = taskDirectories(inputDir).sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
  if (tasks.length === 0) throw new Error(`No task folders containing task.js found in: ${inputDir}`);

  const seenNames = new Set();
  const files = [];
  for (const task of tasks) {
    assertSafeName(task.name);
    if (seenNames.has(task.name)) throw new Error(`Duplicate task name: ${task.name}`);
    seenNames.add(task.name);
    files.push(...collectFiles(task.dir, `tasks/${task.name}`));
    if (!files.some((file) => file.archivePath === `tasks/${task.name}/task.js`)) {
      throw new Error(`Missing task.js in task: ${task.name}`);
    }
  }
  if (files.length > MAX_ENTRIES) throw new Error('Too many files for a standard ZIP archive.');
  if (inputDir === outputZip || outputZip.startsWith(`${inputDir}${path.sep}`)) {
    throw new Error('Output ZIP must be outside the input tasks directory.');
  }

  fs.mkdirSync(path.dirname(outputZip), { recursive: true });
  const tempOutput = `${outputZip}.tmp-${process.pid}`;
  try {
    fs.writeFileSync(tempOutput, makeArchive(files));
    fs.renameSync(tempOutput, outputZip);
  } catch (error) {
    fs.rmSync(tempOutput, { force: true });
    throw error;
  }

  console.log(`Packaged ${tasks.length} tasks (${files.length} files):`);
  for (const task of tasks) console.log(`  - ${task.name}`);
  console.log(`ZIP: ${outputZip}`);
}

try {
  main();
} catch (error) {
  console.error(`Package failed: ${error.message}`);
  process.exitCode = 1;
}
