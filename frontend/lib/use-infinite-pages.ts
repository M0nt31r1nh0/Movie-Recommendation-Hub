import { useCallback, useEffect, useRef, useState } from "react";
import type { Page } from "./api";

/**
 * Loads `fetchPage(0)`, `fetchPage(1)`, ... whenever the element behind `sentinelRef` (put it after
 * the list) scrolls into view, until the last page. `fetchPage` should be memoised; to start over
 * with different parameters, remount the component (give it a new `key`).
 */
export function useInfinitePages<T>(fetchPage: (page: number) => Promise<Page<T>>) {
  const [items, setItems] = useState<T[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Mutable paging state; a ref so overlapping scroll events can't request the same page twice.
  const cursor = useRef({ nextPage: 0, done: false, busy: false });
  const sentinelRef = useRef<HTMLDivElement>(null);

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

  return { items, total, loading, error, sentinelRef };
}
