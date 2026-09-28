import type { PageOptions } from "@/lib/api";

/** The orderings the API supports, as offered in the sort dropdown. The key is what goes in the URL. */
export const SORT_OPTIONS = {
  relevance: { label: "Best match", params: {} }, // searches only: the search API's own order
  newest: { label: "Newest first", params: { sort: "releaseDate", order: "desc" } },
  oldest: { label: "Oldest first", params: { sort: "releaseDate", order: "asc" } },
  "rating-desc": { label: "Highest rated", params: { sort: "rating", order: "desc" } },
  "rating-asc": { label: "Lowest rated", params: { sort: "rating", order: "asc" } },
  "title-asc": { label: "Title A–Z", params: { sort: "title", order: "asc" } },
  "title-desc": { label: "Title Z–A", params: { sort: "title", order: "desc" } },
} satisfies Record<string, { label: string; params: PageOptions }>;

export type SortKey = keyof typeof SORT_OPTIONS;

/** Newest first when browsing, best match when searching. */
export function defaultSortKey(searching: boolean): SortKey {
  return searching ? "relevance" : "newest";
}

/** The sort keys that make sense for a browse or a search. */
export function sortKeysFor(searching: boolean): SortKey[] {
  return (Object.keys(SORT_OPTIONS) as SortKey[]).filter((key) => searching || key !== "relevance");
}

/** The URL's sort key, or the default when it's missing or doesn't apply. */
export function parseSortKey(value: string | null, searching: boolean): SortKey {
  return sortKeysFor(searching).find((key) => key === value) ?? defaultSortKey(searching);
}
