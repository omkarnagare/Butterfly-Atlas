import { describe, expect, test } from 'vitest';
import { oakblueMarkup } from './oakblue.js';

const count = (html, needle) => html.split(needle).length - 1;

describe('oakblueMarkup', () => {
  test('draws four wings, near and far, fore and hind', () => {
    const html = oakblueMarkup(false);
    expect(count(html, 'class="wg ')).toBe(4);
    expect(html).toContain('class="wg near f"');
    expect(html).toContain('class="wg near h"');
    expect(html).toContain('class="wg far f"');
    expect(html).toContain('class="wg far h"');
  });

  test('gives every wing two painted faces', () => {
    const html = oakblueMarkup(false);
    expect(count(html, 'class="face"')).toBe(4);
    expect(count(html, 'class="face back"')).toBe(4);
  });

  test('adds the daisy only when asked', () => {
    expect(oakblueMarkup(false)).not.toContain('class="fl"');
    expect(oakblueMarkup(true)).toContain('class="fl"');
  });

  test('uses unique gradient and clip ids across instances', () => {
    const ids = [oakblueMarkup(false), oakblueMarkup(false)]
      .join('')
      .match(/id="([^"]+)"/g);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
