import { WAITING } from './states.js';
/**
 * Words for the taxonomy update (Phase 5b contract §5, §6): the footer's line, the update screen's progress, the brief
 * /api/status carries (`taxonomyUpdate`) made from an Update answered by Apply, Retry or Merge.
 */
const num = (n) => n.toLocaleString();

/** "Updating taxonomy… 120 of 340 photos", or what a waiting update waits for. */
export function updateLine({ state, waitingFor, done = 0, total = 0 }) {
  if (state === WAITING) {
    if (waitingFor === 'import') return 'Waiting for the import to finish filing…';
    if (waitingFor === 'export') return 'Waiting for the export to finish copying…';
    if (waitingFor === 'backup') return 'Waiting for the backup to finish copying…';
    // Several windows A7: a paused backup that holds the photos holds the update until it is continued or discarded.
    if (waitingFor === 'backup_paused') return 'Waiting for the paused backup. Continue it and let it finish, or discard it.';
    if (waitingFor === 'startup') return 'Waiting for the startup checks to finish…';
    return 'Waiting to update the taxonomy…';
  }
  return total ? `Updating taxonomy… ${num(done)} of ${num(total)} photos` : 'Updating taxonomy…';
}

/** "Step 1 of 2 · 120 of 340 photos" (the step only when there are several); the plan and the switch-over say so. */
export function progressWords({ step, steps, stage, done = 0, total = 0 }) {
  const parts = [];
  if (steps > 1) parts.push(`Step ${num(step)} of ${num(steps)}`);
  if (stage === 'plan' && !total) parts.push('Working out what moves…');
  else parts.push(`${num(done)} of ${num(total)} photos`);
  if (stage === 'switch') parts.push('Updating names and groups…');
  return parts.join(' · ');
}

/** The status's brief (`taxonomyUpdate`) of an Update. */
export function briefOf(update) {
  const p = update.progress ?? {};
  return {
    state: update.state,
    kind: update.kind ?? 'bundle',
    later: update.later === true,
    done: p.done ?? 0,
    total: p.total ?? 0,
    step: p.step ?? 0,
    steps: p.steps ?? 0,
    continuing: p.continuing === true,
    waitingFor: p.waitingFor ?? null,
    notice: update.notice ?? null,
  };
}

/** What a paused update says; the server's notice, or plain words when it sent none. */
export const pausedNoticeOf = (update) => update.notice?.message || 'The taxonomy update stopped. Choose Retry to try again.';

/** Each kind of change, as the summary lists them (in this order). */
export const KIND_LABELS = {
  rename: 'Renamed', move: 'Reclassified', merge: 'Merged', split: 'Split', add: 'Added', remove: 'Removed from the list',
};
const VERBS = { rename: 'renamed', move: 'reclassified', merge: 'merged', split: 'split', add: 'added', remove: 'removed' };

/**
 * "12 species reclassified, 2 renamed, 1 split — affects 340 of your photos.": each kind in the order sent, the first
 * with its noun ("species" when every item of it is a species, else "name(s)").
 */
export function headline({ photos, kinds }) {
  const parts = kinds.map((k, i) => {
    if (i) return `${num(k.count)} ${VERBS[k.kind] ?? k.kind}`;
    const noun = k.items.every((item) => item.rank === 'species') ? 'species' : k.count === 1 ? 'name' : 'names';
    return `${num(k.count)} ${noun} ${VERBS[k.kind] ?? k.kind}`;
  });
  const affects = photos ? `affects ${num(photos)} of your photos.` : 'none of your photos are affected.';
  return `${parts.join(', ')} — ${affects}`;
}

/** "Your atlas: 342 of 1,139 species → 342 of 1,141". */
export const speciesLine = ({ species: { before, after } }) =>
  `Your atlas: ${num(before.got)} of ${num(before.total)} species → ${num(after.got)} of ${num(after.total)}`;

/** The badge on a split species with photos still to sort: "Split — 28 photos to sort". */
export const splitBadge = (n) => `Split — ${n === 1 ? '1 photo' : `${num(n)} photos`} to sort`;
