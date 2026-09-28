import type Keycloak from "keycloak-js";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export type Genre = { id: number; name: string };

export type Movie = {
  id: number;
  title: string;
  description: string | null;
  posterPath: string | null;
  rating: number | null;
  ratingCount: number | null;
  releaseDate: string | null;
  imdbId: string | null;
  genres: Genre[];
};

export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
};

export type SortField = "id" | "title" | "rating" | "releaseDate";

export type PageOptions = {
  page?: number;
  size?: number;
  /** Omitted: GET /movies orders by id, a search by best match. */
  sort?: SortField;
  order?: "asc" | "desc";
};

/** GET /movies: one page of all movies. */
export function listMovies(keycloak: Keycloak, options: PageOptions = {}): Promise<Page<Movie>> {
  return get(keycloak, "/movies", options);
}

/** GET /movies/search: one page of the movies matching `query`. */
export function searchMovies(keycloak: Keycloak, query: string, options: PageOptions = {}): Promise<Page<Movie>> {
  return get(keycloak, "/movies/search", { q: query, ...options });
}

/** GET /movies/{id}, or null if there's no such movie. */
export async function getMovie(keycloak: Keycloak, id: string): Promise<Movie | null> {
  try {
    return await get(keycloak, `/movies/${encodeURIComponent(id)}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

export class ApiError extends Error {
  constructor(readonly status: number, statusText: string) {
    super(`The movies API answered ${status} ${statusText}`);
  }
}

/** Calls the API as the logged-in user, refreshing the access token first if it's about to expire. */
async function get<T>(keycloak: Keycloak, path: string, params: Record<string, string | number | undefined> = {}): Promise<T> {
  await keycloak.updateToken(30);
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) query.set(key, String(value));
  }
  const response = await fetch(`${API_URL}${path}${query.size ? `?${query}` : ""}`, {
    headers: { Authorization: `Bearer ${keycloak.token}` },
  });
  if (!response.ok) {
    throw new ApiError(response.status, response.statusText);
  }
  return response.json();
}

export function posterUrl(posterPath: string, width: "w342" | "w500" = "w342"): string {
  return `https://image.tmdb.org/t/p/${width}${posterPath}`;
}
