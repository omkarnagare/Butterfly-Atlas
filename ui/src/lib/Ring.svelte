<script>
  import { countUp } from './countUp.js';
  import { famColor, famStyle } from './families.js';
  import { butterflySvg, outlineSvg } from './butterfly.js';

  /**
   * The tally's family ring (Meadow design §4): a segment per family in `families` (`{ family, species: { got, total } }`),
   * sized by its species count, a pale tint of the family colour that fills in full colour from its start as the
   * family's species are photographed, so a new atlas is already colourful. Without families, one accent ring.
   * A butterfly perches on it in the colour of the family `perch`, or as an outline while none is given.
   * @type {{ got: number, total: number, families?: { family: string | null, species: { got: number, total: number } }[], perch?: string | null }}
   */
  let { got, total, families = [], perch = null } = $props();
  let pct = $derived(total > 0 ? Math.min(100, Math.max(0, (got / total) * 100)) : 0);

  // Lengths in hundredths of the ring (pathLength 100): 3° between segments, and a photographed share never thinner
  // than about the stroke's width, so a first photo shows.
  const GAP = 3 / 3.6;
  const MIN_FILL = 2.6;
  const round = (n) => Math.round(n * 100) / 100;
  let segments = $derived.by(() => {
    const shown = families.filter((f) => f.species.total > 0);
    const sum = shown.reduce((n, f) => n + f.species.total, 0);
    let at = 0;
    return shown.map((f) => {
      const share = (f.species.total / sum) * 100;
      const len = share - GAP;
      const fill = f.species.got > 0 ? Math.max(MIN_FILL, (Math.min(f.species.got, f.species.total) / f.species.total) * len) : 0;
      const segment = { key: f.family ?? '', colour: famColor(f.family), at: round(at * 3.6), len: round(len), fill: round(Math.min(fill, len)) };
      at += share;
      return segment;
    });
  });
</script>

<div class="ring" role="img" aria-label="{got} of {total} species photographed">
  <svg viewBox="0 0 120 120" aria-hidden="true">
    {#each segments as s (s.key)}
      <g style="--fam:{s.colour}" transform="rotate({s.at} 60 60)">
        <circle class="seg" cx="60" cy="60" r="52" pathLength="100" stroke-dasharray="{s.len} 100" />
        {#if s.fill}<circle class="fill" cx="60" cy="60" r="52" pathLength="100" stroke-dasharray="{s.fill} 100" />{/if}
      </g>
    {:else}
      <circle class="trk" cx="60" cy="60" r="52" />
      <circle class="prog" cx="60" cy="60" r="52" pathLength="100" style="stroke-dashoffset:{100 - pct}" />
    {/each}
  </svg>
  <span class="perch" aria-hidden="true" style={perch ? famStyle(perch) : undefined}>{@html perch ? butterflySvg : outlineSvg}</span>
  <div class="lbl" aria-hidden="true">
    <span class="num" use:countUp={got}>{got}</span>
    <span class="of">of {total} species</span>
  </div>
</div>
