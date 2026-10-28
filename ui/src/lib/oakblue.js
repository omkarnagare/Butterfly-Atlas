// Centaur Oakblue logo rig (draft). Four wings, each with two painted faces, plus body and
// an optional daisy. Ported from docs/ui/logo.html. Styling lives in styles/oakblue.css.
let n = 0;
const VB = '-100 -110 200 160';
const FW = 'M-14,0 C-22,-30 -24,-70 -8,-96 C10,-92 40,-66 46,-36 C40,-22 34,-10 26,0 Z';
const HW = 'M-2,0 C10,-14 40,-40 62,-34 C74,-22 72,0 62,12 L72,34 L56,16 C40,22 16,16 -2,0 Z';
const spot = (x, y, rx, ry, r) => `<ellipse cx="${x}" cy="${y}" rx="${rx}" ry="${ry}" transform="rotate(${r} ${x} ${y})" fill="#4b2e1f" stroke="#f2e4c8" stroke-width="1.8"/>`;
const FSPOTS = spot(-6,-60,7,4.5,-70) + spot(-9,-40,6,4,-70) + spot(10,-78,5,3.5,-40) + spot(18,-66,5.5,3.8,-40) + spot(25,-54,5.5,3.8,-40) + spot(31,-42,5,3.5,-40)
  + '<path d="M-4,-88 C14,-82 34,-62 40,-38" stroke="#f2e4c8" stroke-opacity=".5" stroke-width="1.4" stroke-dasharray="3 3" fill="none"/>';
const HSPOTS = spot(10,-12,5,3.5,-30) + spot(22,-22,5.5,3.8,-30) + spot(36,-28,5.5,3.8,-20) + spot(50,-24,5,3.5,0) + spot(30,-8,5,3.5,-20) + spot(44,-8,4.5,3.2,0)
  + '<path d="M58,-30 C70,-18 68,0 60,10" stroke="#f2e4c8" stroke-opacity=".5" stroke-width="1.4" stroke-dasharray="3 3" fill="none"/>'
  + '<ellipse cx="54" cy="8" rx="8" ry="5" fill="#4fcab5"/><circle cx="58" cy="10" r="3" fill="#141019"/>';
function upper(d, part){
  const u = 'k' + (++n);
  return `<defs><linearGradient id="${u}b" x1="0" y1="1" x2=".6" y2="0"><stop offset="0" stop-color="#24187c"/><stop offset=".5" stop-color="#5949e3"/><stop offset="1" stop-color="#9d8fff"/></linearGradient>
    <linearGradient id="${u}s" x1="0" y1="1" x2="1" y2="0"><stop offset=".25" stop-color="#fff" stop-opacity="0"/><stop offset=".55" stop-color="#efebff" stop-opacity=".95"/><stop offset=".8" stop-color="#fff" stop-opacity="0"/></linearGradient>
    <clipPath id="${u}c"><path d="${d}"/></clipPath></defs>
    <g clip-path="url(#${u}c)"><path d="${d}" fill="url(#${u}b)"/><path class="sheen" d="${d}" fill="url(#${u}s)"/><path d="${d}" fill="none" stroke="#141019" stroke-width="${part === 'f' ? 17 : 15}"/></g>`;
}
function under(d, part){
  const u = 'k' + (++n);
  return `<defs><linearGradient id="${u}g" x1="0" y1="1" x2=".3" y2="0"><stop offset="0" stop-color="#734a33"/><stop offset="1" stop-color="#9c6e50"/></linearGradient>
    <clipPath id="${u}c"><path d="${d}"/></clipPath></defs>
    <g clip-path="url(#${u}c)"><path d="${d}" fill="url(#${u}g)"/>${part === 'f' ? FSPOTS : HSPOTS}<path d="${d}" fill="none" stroke="#3a2317" stroke-width="8"/></g>`;
}
function wing(d, part, side){
  const front = side === 'near' ? under(d, part) : upper(d, part);
  const back = side === 'near' ? upper(d, part) : under(d, part);
  return `<span class="wg ${side} ${part}"><svg class="face" viewBox="${VB}" aria-hidden="true">${front}</svg><svg class="face back" viewBox="${VB}" aria-hidden="true"><g transform="scale(-1,1)">${back}</g></svg></span>`;
}
const ANT = 'M-31,-4 C-40,-26 -52,-44 -62,-56';
const BODY = `<svg class="bd" viewBox="${VB}" aria-hidden="true">
  <path d="M-27,-5 C-34,-28 -44,-46 -52,-60" stroke="#141019" stroke-width="1.6" fill="none" stroke-linecap="round" opacity=".7"/>
  <path d="${ANT}" stroke="#141019" stroke-width="2" fill="none" stroke-linecap="round"/><path d="${ANT}" stroke="#f4f0e6" stroke-width="2" fill="none" stroke-dasharray="1.6 4"/>
  <ellipse cx="-63" cy="-57" rx="2.6" ry="4" transform="rotate(-35 -63 -57)" fill="#141019"/>
  <path d="M-18,10 l-8,18 M-10,11 l-2,19 M-2,10 l5,18" stroke="#141019" stroke-width="1.7" stroke-linecap="round"/>
  <ellipse cx="18" cy="8" rx="28" ry="5.5" fill="#2b2333"/><ellipse cx="-12" cy="5" rx="13" ry="8" fill="#2b2333"/>
  <circle cx="-29" cy="2" r="7" fill="#2b2333"/><circle cx="-31" cy="1" r="3.6" fill="#5a4636"/><circle cx="-32" cy="0" r="1.1" fill="#fff"/></svg>`;
let petals = '';
for (let i = 0; i < 12; i++) petals += `<ellipse cx="32" rx="30" ry="9" fill="#f6f3ea" stroke="#d6d0c0" stroke-width="1" transform="rotate(${i * 30})"/>`;
const FLOWER = `<svg class="fl" viewBox="${VB}" aria-hidden="true"><path d="M-8,40 C-6,66 -12,90 -16,130" stroke="#3f6a32" stroke-width="5" fill="none"/>
  <g transform="translate(-8 34) scale(1 .4)">${petals}</g><ellipse cx="-8" cy="34" rx="17" ry="8" fill="#e3a52a"/><ellipse cx="-8" cy="33" rx="9" ry="4.4" fill="#c7841c"/></svg>`;

/** Markup for one oakblue. Wrap it in an element with classes `oak` and one of `perch`, `fly`, `rest`. */
export function oakblueMarkup(flower) {
  return `${flower ? FLOWER : ''}<span class="scene">${wing(HW, 'h', 'far')}${wing(FW, 'f', 'far')}${BODY}${wing(HW, 'h', 'near')}${wing(FW, 'f', 'near')}</span>`;
}
