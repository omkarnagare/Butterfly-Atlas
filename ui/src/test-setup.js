// Tests behave as if the OS "reduce motion" setting were on, so counts and transitions show their
// final state at once. Individual tests can replace window.matchMedia to exercise the animations.
import { afterEach } from 'vitest';

const reduced = (query) => ({
  matches: query.includes('reduce'),
  media: query,
  addEventListener() {},
  removeEventListener() {},
  addListener() {},
  removeListener() {},
});

window.matchMedia = reduced;

afterEach(() => {
  window.matchMedia = reduced;
  window.location.hash = '';
  try {
    localStorage.clear();
  } catch {
    // storage unavailable: nothing to clear
  }
});
