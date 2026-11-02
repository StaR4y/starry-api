/**
 * Links that remote data decides (the news feed, the benchmark source) go into an href only as absolute http(s)
 * addresses without credentials, so that a feed cannot put a javascript: or data: address behind a click.
 */

/** The address itself when it is safe to link to, otherwise null. */
export function webLink(value: string | null | undefined): string | null {
  const candidate = (value ?? '').trim();
  if (!candidate || /[\s"<>\\]/.test(candidate)) return null;
  try {
    const url = new URL(candidate);
    const web = url.protocol === 'https:' || url.protocol === 'http:';
    return web && url.hostname && !url.username && !url.password ? candidate : null;
  } catch {
    return null;
  }
}

/** The host of a link that {@link webLink} accepted, for the text of the link. */
export function webHost(link: string): string {
  try {
    return new URL(link).hostname.replace(/^www\./, '');
  } catch {
    return link;
  }
}
