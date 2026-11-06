/** "today", "yesterday" or "N days ago", counted in calendar days on this computer's clock. */
export function ago(iso, now = new Date()) {
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return '';
  const day = (d) => Math.floor((d.getTime() - d.getTimezoneOffset() * 60000) / 86400000);
  const days = day(now) - day(then);
  if (days <= 0) return 'today';
  return days === 1 ? 'yesterday' : `${days} days ago`;
}

/** "27 October", with the year when it is not this year's ("3 March 2025"); '' for a bad date. `locale` is for tests. */
export function longDate(iso, { now = new Date(), locale } = {}) {
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return '';
  const options = { day: 'numeric', month: 'long' };
  if (then.getFullYear() !== now.getFullYear()) options.year = 'numeric';
  return new Intl.DateTimeFormat(locale, options).format(then);
}

/** "12 Oct", with the year when it is not this year's ("3 Mar 2025"); '' for a bad date. `locale` is for tests. */
export function shortDate(iso, { now = new Date(), locale } = {}) {
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return '';
  const options = { day: 'numeric', month: 'short' };
  if (then.getFullYear() !== now.getFullYear()) options.year = 'numeric';
  return new Intl.DateTimeFormat(locale, options).format(then);
}

// One device at a time (one-device-library contract): the server's instants (ISO-8601, UTC) in this device's local time.
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const valid = (iso) => {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d;
};
const two = (n) => String(n).padStart(2, '0');

/** "11:05" (24-hour, local time); '' for a bad or missing instant. */
export function clockOf(iso) {
  const d = valid(iso);
  return d ? `${two(d.getHours())}:${two(d.getMinutes())}` : '';
}

/** "4 Nov at 11:05"; '' for a bad or missing instant. */
export function dayAt(iso) {
  const d = valid(iso);
  return d ? `${d.getDate()} ${MONTHS[d.getMonth()]} at ${clockOf(iso)}` : '';
}

/** "4 Nov, 14:02"; '' for a bad or missing instant. */
export function dayClock(iso) {
  const d = valid(iso);
  return d ? `${d.getDate()} ${MONTHS[d.getMonth()]}, ${clockOf(iso)}` : '';
}

/** Whole minutes from `iso` to `now` by this device's clock; an instant in the future (or a bad one) counts as 0. */
export function minutesSince(iso, now = new Date()) {
  const d = valid(iso);
  if (!d) return 0;
  return Math.max(0, Math.floor((now.getTime() - d.getTime()) / 60000));
}
