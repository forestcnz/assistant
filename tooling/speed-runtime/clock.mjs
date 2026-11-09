// Each clock keeps its own origin. Changing speed never jumps the virtual timeline.
export class ScaledClock {
  constructor() { this.origins = new Map(); this.factor = 1; }
  read(id, real) {
    let origin = this.origins.get(id);
    if (!origin) {
      origin = { real, virtual: real };
      this.origins.set(id, origin);
    }
    return origin.virtual + (real - origin.real) * this.factor;
  }
  setFactor(next, realNow) {
    if (!Number.isFinite(next) || next < 0.25 || next > 10) throw new Error('Invalid speed');
    for (const [id, origin] of this.origins) {
      const now = realNow(id);
      origin.virtual += (now - origin.real) * this.factor;
      origin.real = now;
    }
    this.factor = next;
  }
}
