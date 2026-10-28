/** Remembers Cards vs Map per viewer. Storage can be missing (private windows), so failures are ignored. */
const KEY = 'atlas.exploreView';

export function loadViewMode() {
  try {
    return localStorage.getItem(KEY) === 'map' ? 'map' : 'cards';
  } catch {
    return 'cards';
  }
}

export function saveViewMode(mode) {
  try {
    localStorage.setItem(KEY, mode);
  } catch {
    // keep the choice for this visit only
  }
}
