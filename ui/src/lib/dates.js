/** "today", "yesterday" or "N days ago", counted in calendar days on this computer's clock. */
export function ago(iso, now = new Date()) {
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return '';
  const day = (d) => Math.floor((d.getTime() - d.getTimezoneOffset() * 60000) / 86400000);
  const days = day(now) - day(then);
  if (days <= 0) return 'today';
  return days === 1 ? 'yesterday' : `${days} days ago`;
}

/** "12 Oct", with the year when it is not this year's ("3 Mar 2025"); '' for a bad date. `locale` is for tests. */
export function shortDate(iso, { now = new Date(), locale } = {}) {
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return '';
  const options = { day: 'numeric', month: 'short' };
  if (then.getFullYear() !== now.getFullYear()) options.year = 'numeric';
  return new Intl.DateTimeFormat(locale, options).format(then);
}
