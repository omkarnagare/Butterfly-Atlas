// The prototype's open-winged butterfly (docs/ui/prototype.html). Colours come from CSS classes so
// each card takes its family colour from --fam. Markup strings, like oakblue.js, keep the SVG
// namespace intact when inserted with {@html}.
const FW = 'M2,-2 C14,-34 58,-62 92,-52 C100,-36 86,-10 60,2 C40,8 18,6 2,2 Z';
const HW = 'M2,2 C26,4 58,12 66,34 C70,56 44,70 22,60 C10,52 4,30 2,6 Z';
const VB = '-104 -70 208 170';

const SIDE = `<path class="w" d="${FW}"/><path class="e" d="${FW}"/>`
  + '<circle class="s" cx="66" cy="-46" r="4.5"/><circle class="s" cx="78" cy="-38" r="4"/>'
  + '<circle class="s" cx="57" cy="-52" r="3.4"/><circle class="s" cx="44" cy="-22" r="5"/>'
  + `<path class="w" d="${HW}"/><path class="e" d="${HW}"/>`
  + '<circle class="s" cx="50" cy="50" r="4"/><circle class="s" cx="60" cy="37" r="3.5"/><circle class="s" cx="35" cy="58" r="3.5"/>';
const WINGS = `<g>${SIDE}</g><g transform="scale(-1,1)">${SIDE}</g>`;
const BODY = '<ellipse class="b" cx="0" cy="8" rx="4.5" ry="24"/><circle class="b" cx="0" cy="-18" r="5.5"/>'
  + '<path class="ant" d="M-1.5,-22 C-6,-42 -13,-54 -20,-62 M1.5,-22 C6,-42 13,-54 20,-62"/>'
  + '<circle class="b" cx="-20" cy="-62" r="2.6"/><circle class="b" cx="20" cy="-62" r="2.6"/>';

export const butterflySvg = `<svg class="bf" viewBox="${VB}" aria-hidden="true">${WINGS}${BODY}</svg>`;
export const heroSvg = `<svg class="bf wings" viewBox="${VB}" aria-hidden="true">${WINGS}</svg>`
  + `<svg class="bf" viewBox="${VB}" aria-hidden="true">${BODY}</svg>`;
/** A species not yet photographed (Meadow design §3): a soft outline tinted in its family colour (by CSS, from --fam). */
export const outlineSvg = `<svg class="bf-out" viewBox="${VB}" aria-hidden="true"><path class="o" d="${FW}"/><path class="o" d="${HW}"/>`
  + `<g transform="scale(-1,1)"><path class="o" d="${FW}"/><path class="o" d="${HW}"/></g></svg>`;

/** Small open-wing mark for the "adult" life stage and full-life-cycle badges; filled by CSS. */
export const adultSvg = `<svg viewBox="${VB}" aria-hidden="true"><path d="${FW}"/><path d="${HW}"/>`
  + `<g transform="scale(-1,1)"><path d="${FW}"/><path d="${HW}"/></g></svg>`;
