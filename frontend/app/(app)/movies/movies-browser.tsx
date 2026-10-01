"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useState } from "react";
import { listMovies, searchMovies } from "@/lib/api";
import { useInfinitePages } from "@/lib/use-infinite-pages";
import { useKeycloak } from "../auth-gate";
import MovieCard from "./movie-card";
import RecommendedRow from "./recommended-row";
import { defaultSortKey, parseSortKey, SORT_OPTIONS, sortKeysFor, type SortKey } from "./sort-options";

const PAGE_SIZE = 24;

/**
 * The movies page: a search bar and sort dropdown above an infinitely scrolling grid, with the user's
 * "Recommended for you" on top when they aren't searching. The search and sort live in the URL
 * (?q=...&sort=...), so they survive reloads and the back button.
 */
export default function MoviesBrowser() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const query = searchParams.get("q")?.trim() ?? "";
  const sortKey = parseSortKey(searchParams.get("sort"), query !== "");

  // Only a sort the user picked goes in the URL, so a search started from the default (newest first)
  // list gets the search's default (best match) rather than inheriting newest first.
  const updateUrl = (changes: { q?: string; sort?: SortKey }) => {
    const q = changes.q ?? query;
    const sort = parseSortKey(changes.sort ?? searchParams.get("sort"), q !== "");
    const params = new URLSearchParams();
    if (q) params.set("q", q);
    if (sort !== defaultSortKey(q !== "")) params.set("sort", sort);
    const search = params.toString();
    router.replace(search ? `${pathname}?${search}` : pathname, { scroll: false });
  };

  return (
    <main className="flex-1 px-6 py-6">
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <SearchBar key={query} initialQuery={query} onSearch={(q) => updateUrl({ q })} />
        <label className="flex items-center gap-2 text-sm">
          <span className="text-foreground/70">Sort by</span>
          <select
            value={sortKey}
            onChange={(event) => updateUrl({ sort: event.target.value as SortKey })}
            className="rounded-md border border-foreground/20 bg-background px-3 py-2"
          >
            {sortKeysFor(query !== "").map((key) => (
              <option key={key} value={key}>
                {SORT_OPTIONS[key].label}
              </option>
            ))}
          </select>
        </label>
      </div>
      {!query && <RecommendedRow />}
      {/* A new search or sort starts a fresh list: remounting resets its pages and scroll state. */}
      <MovieResults key={`${sortKey}|${query}`} query={query} sortKey={sortKey} />
    </main>
  );
}

function SearchBar({ initialQuery, onSearch }: { initialQuery: string; onSearch: (query: string) => void }) {
  const [text, setText] = useState(initialQuery);
  return (
    <form
      role="search"
      className="min-w-64 flex-1"
      onSubmit={(event) => {
        event.preventDefault();
        onSearch(text.trim());
      }}
    >
      <input
        type="search"
        value={text}
        onChange={(event) => setText(event.target.value)}
        placeholder="Search by title or description, then press Enter"
        aria-label="Search movies"
        maxLength={200}
        className="w-full rounded-md border border-foreground/20 bg-background px-4 py-2"
      />
    </form>
  );
}

/** One search or sort's results, loading the next page whenever the end of the grid scrolls into view. */
function MovieResults({ query, sortKey }: { query: string; sortKey: SortKey }) {
  const keycloak = useKeycloak();
  const fetchPage = useCallback(
    (page: number) => {
      const options = { ...SORT_OPTIONS[sortKey].params, page, size: PAGE_SIZE };
      return query ? searchMovies(keycloak, query, options) : listMovies(keycloak, options);
    },
    [keycloak, query, sortKey],
  );
  // Opening a movie remembers the list, so going back shows it as it was.
  const { items: movies, total, loading, error, sentinelRef, remember } = useInfinitePages(fetchPage, `movies|${sortKey}|${query}`);

  return (
    <>
      {total !== null && (
        <p className="mb-4 text-sm text-foreground/70">
          {query
            ? `${total.toLocaleString()} ${total === 1 ? "result" : "results"} for “${query}”`
            : `${total.toLocaleString()} movies`}
        </p>
      )}
      <ul onClickCapture={remember} className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-6">
        {movies.map((movie) => (
          <MovieCard key={movie.id} movie={movie} />
        ))}
      </ul>
      <div ref={sentinelRef} className="h-px" />
      {loading && <p className="py-6 text-center text-sm text-foreground/60">Loading…</p>}
      {error && <p className="py-6 text-red-600">Couldn&apos;t load movies: {error}</p>}
      {!loading && !error && total === 0 && <p className="text-foreground/70">No movies found.</p>}
    </>
  );
}
