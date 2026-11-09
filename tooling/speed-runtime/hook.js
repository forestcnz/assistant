import Java from 'frida-java-bridge';
import { ScaledClock } from './clock.mjs';

const clock = new ScaledClock();
let factor = 1, nativeEnabled = true, statePath, statusPath;
let javaReady = false, nativeHooks = 0, nativeCalls = 0, lastError = '';
let animator, systemScale = 1, realClock;
const modules = new Map();

function report() {
  try {
    File.writeAllText(statusPath, JSON.stringify({ javaReady, nativeHooks, nativeCalls, error: lastError }));
  } catch (_) { /* The provider reports a missing status instead of claiming success. */ }
}
function readSpec(pointer) {
  return Process.pointerSize === 8
    ? pointer.readS64().toNumber() + pointer.add(8).readS64().toNumber() / 1e9
    : pointer.readS32() + pointer.add(4).readS32() / 1e9;
}
function writeSpec(pointer, time) {
  const seconds = Math.floor(time);
  const nanos = Math.min(999999999, Math.max(0, Math.floor((time - seconds) * 1e9)));
  if (Process.pointerSize === 8) {
    pointer.writeS64(seconds); pointer.add(8).writeS64(nanos);
  } else {
    pointer.writeS32(seconds); pointer.add(4).writeS32(nanos);
  }
}
function readRealClock(id) {
  const buffer = Memory.alloc(16);
  if (realClock(id, buffer) !== 0) throw new Error('Cannot read monotonic clock');
  return readSpec(buffer);
}
function update() {
  try {
    const state = JSON.parse(File.readAllText(statePath));
    const next = Number(state.factor);
    if (!Number.isFinite(next) || next < 0.25 || next > 10) return;
    const nextNative = state.nativeEnabled === true;
    const nextClockFactor = nextNative ? next : 1;
    if (realClock && nextClockFactor !== clock.factor) clock.setFactor(nextClockFactor, readRealClock);
    nativeEnabled = nextNative;
    if (next !== factor) {
      factor = next;
      if (javaReady) Java.performNow(() => { animator.sDurationScale.value = systemScale / factor; });
    }
  } catch (error) { lastError = String(error); }
}
function installJava() {
  Java.performNow(() => {
    animator = Java.use('android.animation.ValueAnimator');
    systemScale = animator.sDurationScale.value;
    const setter = animator.setDurationScale.overload('float');
    setter.implementation = function (scale) {
      systemScale = scale;
      setter.call(this, scale / factor);
    };
    animator.sDurationScale.value = systemScale / factor;
    javaReady = true;
  });
}
function installNative() {
  const libc = Process.getModuleByName('libc.so');
  const address = libc.getExportByName('clock_gettime');
  realClock = new NativeFunction(address, 'int', ['int', 'pointer']);
  // Scope changes to native libraries belonging to this APK. Framework clocks,
  // wall-clock dates, network libraries in /system and other apps are untouched.
  const observer = Process.attachModuleObserver({
    onAdded(module) {
      if ((module.path.startsWith('/data/app/') || module.path.startsWith('/data/user/')) &&
          module.name.endsWith('.so') && !module.name.includes('forest_speed') &&
          !module.name.includes('gadget')) modules.set(module.base.toString(), module);
    },
    onRemoved(module) { modules.delete(module.base.toString()); },
  });
  Interceptor.attach(address, {
    onEnter(args) {
      this.scale = false;
      const id = args[0].toInt32();
      if (id !== 1 && id !== 4 && id !== 7) return;
      if (!nativeEnabled && !clock.origins.has(id)) return;
      const caller = this.returnAddress;
      for (const module of modules.values()) {
        if (caller.compare(module.base) >= 0 && caller.compare(module.base.add(module.size)) < 0) {
          this.scale = true; this.id = id; this.output = args[1]; break;
        }
      }
    },
    onLeave(result) {
      if (!this.scale || result.toInt32() !== 0 || this.output.isNull()) return;
      try {
        writeSpec(this.output, clock.read(this.id, readSpec(this.output)));
        nativeCalls++;
      } catch (error) { lastError = String(error); }
    },
  });
  // Keep observer referenced throughout the script lifetime.
  globalThis.speedModuleObserver = observer;
  nativeHooks++;
}
rpc.exports = {
  init(_stage, parameters) {
    statePath = parameters.statePath; statusPath = parameters.statusPath;
    try { installNative(); } catch (error) { lastError = String(error); }
    update();
    try { installJava(); } catch (error) { lastError = String(error); }
    report();
    setInterval(update, 250);
    setInterval(report, 1000);
  },
};
