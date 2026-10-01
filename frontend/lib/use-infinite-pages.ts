import { useCallback, useEffect, useRef, useState } from "react";
import type { Page } from "./api";

type Saved = { items: unknown[]; total: number | null; nextPage: number; done: boolean; scrollY: number };

// Lists the user left by opening one of their items, so going back shows them as they were: the same
// pages loaded, scrolled to the same place. Only the last few, and only in this tab's memory.
const saved = new Map<string, Saved>();
const KEPT = 5;

/**
 * Loads `fetchPage(0)`, `fetchPage(1)`, ... whenever the element behind `sentinelRef` (put it after
 * the list) scrolls into view, until the last page. `fetchPage` should be memoised; to start over
 * with different parameters, remount the component (give it a new `key`).
 *
 * With a `restoreKey` (e.g. the search), calling `remember()` before leaving the page, say when an
 * item is clicked, lets the list come back as it was when the page is opened again with that key.
 */
export function useInfinitePages<T>(fetchPage: (page: number) => Promise<Page<T>>, restoreKey?: string) {
  const [restored] = useState(() => (restoreKey ? saved.get(restoreKey) : undefined));
  const [items, setItems] = useState<T[]>(() => (restored?.items as T[] | undefined) ?? []);
  const [total, setTotal] = useState<number | null>(restored?.total ?? null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Mutable paging state; a ref so overlapping scroll events can't request the same page twice.
  const cursor = useRef({ nextPage: restored?.nextPage ?? 0, done: restored?.done ?? false, busy: false });
  const sentinelRef = useRef<HTMLDivElement>(null);

  // Back where the user left it, once the restored items are on screen.
  useEffect(() => {
    if (restored) requestAnimationFrame(() => window.scrollTo(0, restored.scrollY));
  }, [restored]);

  const remember = useCallback(() => {
    if (!restoreKey) return;
    saved.delete(restoreKey);
    saved.set(restoreKey, { items, total, nextPage: cursor.current.nextPage, done: cursor.current.done, scrollY: window.scrollY });
    while (saved.size > KEPT) saved.delete(saved.keys().next().value!);
  }, [restoreKey, items, total]);

  const loadNextPage = useCallback(async () => {
    const state = cursor.current;
    if (state.busy || state.done) return;
    state.busy = true;
    setLoading(true);
    try {
      const page = await fetchPage(state.nextPage);
      setItems((loaded) => [...loaded, ...page.items]);
      setTotal(page.totalItems);
      state.nextPage += 1;
      state.done = state.nextPage >= page.totalPages;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Something went wrong");
      state.done = true;
    } finally {
      state.busy = false;
      setLoading(false);
    }
  }, [fetchPage]);

  // A fresh observer after every page: it reports straight away whether the end of the list is still
  // visible, so a screen that isn't full yet keeps loading until it is.
  useEffect(() => {
    const element = sentinelRef.current;
    if (!element) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) void loadNextPage();
      },
      { rootMargin: "800px 0px" },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [loadNextPage, items.length]);

  return { items, total, loading, error, sentinelRef, remember };
}
