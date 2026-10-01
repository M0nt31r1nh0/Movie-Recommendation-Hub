// Which pages the user has visited in this tab, so a "Back" link can return to the page they came from
// (with its search, sort or selected request, all in the URL) instead of a fixed page.
let lastPath: string | null = null;
let visits = 0;

/** Called by the layout whenever the page changes. */
export function recordVisit(path: string) {
  if (path === lastPath) return;
  lastPath = path;
  visits += 1;
}

/** Whether an earlier page of the app is in this tab's history. */
export function canGoBack(): boolean {
  return visits > 1;
}
