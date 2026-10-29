import { parseRoute, photoHref } from './route.js';

/*
 * The photo viewer lives at #/species/<id>/photo/<photoId> (an import's large view at
 * #/import/<id>/photo/<itemId>) so the browser's Back closes it. When it was opened from a thumbnail
 * in the app, closing goes back one step (to wherever the user was); when the address was opened
 * directly, closing replaces it with the page under it instead of leaving the app. Moving between
 * photos replaces the address, so Back never walks through them.
 */
let openedInApp = false;
// The address of the page a viewer opened in the app was opened from (its title links to the species from elsewhere).
let openedFrom = null;

export function noteViewerOpen() {
  openedInApp = true;
  openedFrom = window.location.hash;
}

/** A viewer that closed another way (Back, an address replaced under it) no longer counts as opened in the app. */
export function forgetViewerOpen() {
  openedInApp = false;
  openedFrom = null;
}

/** The address the open viewer was opened from in the app; null when it was opened from its own address. */
export const viewerOpenedFrom = () => (openedInApp ? openedFrom : null);

const isViewer = (route) => route.photoId != null || route.itemId != null;

// Any move to an address that is not a viewer (Back, a link, a typed address) ends the viewer opened in the app,
// so one opened from its address later closes to the page under it instead of going back.
window.addEventListener('hashchange', () => {
  if (!isViewer(parseRoute(window.location.hash))) forgetViewerOpen();
});

/** Closes a viewer whose page underneath is at `href`. */
export function closeViewerTo(href) {
  if (openedInApp) {
    openedInApp = false;
    window.history.back();
  } else {
    window.location.replace(href);
  }
}

export const closeViewer = (taxonId) => closeViewerTo(`#/species/${encodeURIComponent(taxonId)}`);

export function showPhoto(taxonId, photoId, tag = null, place = null, type = null) {
  window.location.replace(photoHref(taxonId, photoId, tag, place, type));
}

/** The viewer's title: shows the photo's species page in place of the viewer, so Back returns to where it was opened. */
export function leaveViewerFor(taxonId) {
  forgetViewerOpen();
  window.location.replace(`#/species/${encodeURIComponent(taxonId)}`);
}
