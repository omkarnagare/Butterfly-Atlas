import { BACKUP_COPYING, TAXONOMY_UPDATING } from './codes.js';
/**
 * The add-photos queue (design §4): files go one request each, at most two at a time, so progress is
 * visible and memory stays flat. Every file ends in one state:
 * added | skipped | addedElsewhereToo (the server's answer), refused (with the reason) or cancelled.
 * A refusal because the taxonomy is updating (Phase 5b, 409 `taxonomy_updating`) or a backup holds the photos (409
 * `backup_copying`, several-windows A4) would refuse every other photo too: the photos still waiting are refused with it,
 * without being sent.
 */
export const REFUSE_ALL = [TAXONOMY_UPDATING, BACKUP_COPYING];

export function uploadAll(files, send, { concurrency = 2, onChange = () => {} } = {}) {
  const items = files.map((file) => ({ file, state: 'waiting', result: null, message: '' }));
  const controllers = new Set();
    let stopped = false;
  let next = 0;
  let refuseRest = null;
  const notify = () => onChange(items.map((item) => ({ ...item })));

  async function worker() {
    while (!stopped && next < items.length) {
      const item = items[next++];
      if (refuseRest) {
        Object.assign(item, { state: 'refused', ...refuseRest });
        notify();
        continue;
      }
      const controller = new AbortController();
      controllers.add(controller);
      item.state = 'sending';
      notify();
      try {
        const result = await send(item.file, controller.signal);
        if (['added', 'skipped', 'addedElsewhereToo'].includes(result?.status)) {
          item.result = result;
          item.state = result.status;
        } else {
          item.state = 'refused';
          item.message = 'Butterfly Atlas could not add this photo.';
        }
      } catch (e) {
        if (e?.name === 'AbortError') {
          item.state = 'cancelled';
        } else {
          item.state = 'refused';
          item.message = e?.message || 'This photo could not be added.';
          if (REFUSE_ALL.includes(e?.code)) {
            item.code = e.code;
            refuseRest = { message: item.message, code: e.code };
          }
        }
      } finally {
        controllers.delete(controller);
      }
      notify();
    }
  }

  const workers = Array.from({ length: Math.max(1, Math.min(concurrency, items.length)) }, worker);
  const done = Promise.all(workers).then(() => {
    for (const item of items) if (item.state === 'waiting') item.state = 'cancelled';
    notify();
    return items.map((item) => ({ ...item }));
  });

  return {
    done,
    cancel() {
      stopped = true;
      for (const controller of controllers) controller.abort();
    },
  };
}

/**
 * Counts what happened to a finished batch; `firstForSpecies`, `milestone` and `speciesCount` come from the server's answers.
 * `unknownPlaceIds` are the added photos whose `place` is null (no GPS place found).
 */
export function summarise(items) {
  const inState = (state) => items.filter((item) => item.state === state);
  const added = items.filter((item) => item.state === 'added' || item.state === 'addedElsewhereToo');
  const counts = items.map((item) => item.result?.speciesCount).filter((c) => typeof c === 'number');
  return {
    total: items.length,
    added: added.length,
    addedIds: added.map((item) => item.result.photo.id),
    unknownPlaceIds: added.filter((item) => item.result.photo.place === null).map((item) => item.result.photo.id),
    // Phase 7b audit 20: HEIC photos added, said apart while the computer can't show them.
    heicAdded: added.filter((item) => item.result.photo?.fileType === 'heic').length,
    skipped: inState('skipped').length,
    elsewhere: inState('addedElsewhereToo').map((item) => ({ name: item.file.name, taxon: item.result.otherTaxon })),
    refused: inState('refused').map((item) => ({ name: item.file.name, message: item.message, ...(item.code ? { code: item.code } : {}) })),
    cancelled: inState('cancelled').length,
    firstForSpecies: added.some((item) => item.result.firstForSpecies),
    milestone: added.map((item) => item.result.milestone).find((m) => m) ?? null,
    speciesCount: counts.length ? Math.max(...counts) : null,
  };
}

/** True when every file was simply added, so the sheet can close without a report. */
export const allAdded = (summary) => summary.total > 0 && summary.added === summary.total;

const count = (n, one, many) => (n === 1 ? one : many.replace('#', n));
const speciesName = (taxon) => taxon.common || taxon.name;

/** Plain-language report lines for the add sheet (refusals are listed per file beside them). */
export function summaryLines(summary, taxon) {
  const lines = [];
  lines.push(summary.added ? count(summary.added, '1 photo added.', '# photos added.') : 'No photos were added.');
  if (summary.skipped) {
    lines.push(count(summary.skipped,
      `1 photo was already in ${speciesName(taxon)} and was skipped.`,
      `# photos were already in ${speciesName(taxon)} and were skipped.`));
  }
  const elsewhere = new Map();
  for (const { taxon: other } of summary.elsewhere) {
    const entry = elsewhere.get(other.id) ?? { taxon: other, n: 0 };
    entry.n += 1;
    elsewhere.set(other.id, entry);
  }
  for (const { taxon: other, n } of elsewhere.values()) {
    lines.push(count(n, `1 photo is also filed under ${speciesName(other)}.`, `# photos are also filed under ${speciesName(other)}.`));
  }
  if (summary.cancelled) {
    lines.push(count(summary.cancelled, '1 photo was not added because you stopped.', '# photos were not added because you stopped.'));
  }
  return lines;
}

/** The second sentence of the add message: "3 have an unknown place.", or '' when every added photo has a place. */
export function unknownPlaceLine(summary) {
  const n = summary.unknownPlaceIds.length;
  if (!n) return '';
  if (summary.added === 1) return 'It has an unknown place.';
  return n === 1 ? '1 has an unknown place.' : `${n} have an unknown place.`;
}

/** Whether a file matches an `accept` list (mime types and .extensions), as the file input would. */
export function acceptedBy(accept, file) {
  const type = (file.type || '').toLowerCase();
  const name = (file.name || '').toLowerCase();
  return accept.split(',').map((t) => t.trim().toLowerCase()).filter(Boolean)
    .some((t) => (t.startsWith('.') ? name.endsWith(t) : t === type));
}
