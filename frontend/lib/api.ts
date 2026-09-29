import type Keycloak from "keycloak-js";

const MOVIES_API_URL = process.env.NEXT_PUBLIC_MOVIES_API_URL ?? "http://localhost:8080";
const PROFILES_API_URL = process.env.NEXT_PUBLIC_PROFILES_API_URL ?? "http://localhost:8081";

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
  /** Omitted: GET /movies lists newest first, a search by best match. */
  sort?: SortField;
  order?: "asc" | "desc";
};

export type ProfileSummary = { id: string; username: string };

export type Profile = ProfileSummary & {
  createdAt: string;
  favoriteGenres: Genre[];
  watchedCount: number;
  followingCount: number;
  followersCount: number;
  /** Whether this is the logged-in user's own profile. */
  me: boolean;
  /** Whether the logged-in user follows this profile. */
  followedByMe: boolean;
};

export type WatchedMovie = {
  movieId: number;
  title: string;
  posterPath: string | null;
  releaseDate: string | null;
  watchedAt: string;
};

// movies-service

/** GET /movies: one page of all movies. */
export function listMovies(keycloak: Keycloak, options: PageOptions = {}): Promise<Page<Movie>> {
  return request(keycloak, "GET", MOVIES_API_URL, "/movies", { params: options });
}

/** GET /movies/search: one page of the movies matching `query`. */
export function searchMovies(keycloak: Keycloak, query: string, options: PageOptions = {}): Promise<Page<Movie>> {
  return request(keycloak, "GET", MOVIES_API_URL, "/movies/search", { params: { q: query, ...options } });
}

/** GET /movies/{id}, or null if there's no such movie. */
export function getMovie(keycloak: Keycloak, id: string): Promise<Movie | null> {
  return orNullIfNotFound(request(keycloak, "GET", MOVIES_API_URL, `/movies/${encodeURIComponent(id)}`));
}

/** GET /genres: every movie category, by name. */
export function listGenres(keycloak: Keycloak): Promise<Genre[]> {
  return request(keycloak, "GET", MOVIES_API_URL, "/genres");
}

// profiles-service. Wherever a profile id is taken, "me" means the logged-in user.

/** GET /profiles: profiles whose username starts with `query`, or all of them, by username. */
export function searchProfiles(keycloak: Keycloak, query: string, options: PageOptions = {}): Promise<Page<ProfileSummary>> {
  return request(keycloak, "GET", PROFILES_API_URL, "/profiles", { params: { q: query || undefined, ...options } });
}

/** GET /profiles/{id}, or null if there's no such profile. */
export function getProfile(keycloak: Keycloak, id: string): Promise<Profile | null> {
  return orNullIfNotFound(request(keycloak, "GET", PROFILES_API_URL, `/profiles/${encodeURIComponent(id)}`));
}

export function listWatched(keycloak: Keycloak, profileId: string, options: PageOptions = {}): Promise<Page<WatchedMovie>> {
  return request(keycloak, "GET", PROFILES_API_URL, `/profiles/${encodeURIComponent(profileId)}/watched`, { params: options });
}

export function listFollowing(keycloak: Keycloak, profileId: string, options: PageOptions = {}): Promise<Page<ProfileSummary>> {
  return request(keycloak, "GET", PROFILES_API_URL, `/profiles/${encodeURIComponent(profileId)}/following`, { params: options });
}

export function listFollowers(keycloak: Keycloak, profileId: string, options: PageOptions = {}): Promise<Page<ProfileSummary>> {
  return request(keycloak, "GET", PROFILES_API_URL, `/profiles/${encodeURIComponent(profileId)}/followers`, { params: options });
}

/** Replaces the logged-in user's favourite genres. */
export function setFavoriteGenres(keycloak: Keycloak, genreIds: number[]): Promise<Profile> {
  return request(keycloak, "PUT", PROFILES_API_URL, "/profiles/me/favorite-genres", { body: genreIds });
}

/** Whether the logged-in user has marked the movie as watched. */
export async function isWatched(keycloak: Keycloak, movieId: number): Promise<boolean> {
  return (await orNullIfNotFound(request(keycloak, "GET", PROFILES_API_URL, `/profiles/me/watched/${movieId}`))) !== null;
}

export function setWatched(keycloak: Keycloak, movieId: number, watched: boolean): Promise<void> {
  return request(keycloak, watched ? "PUT" : "DELETE", PROFILES_API_URL, `/profiles/me/watched/${movieId}`);
}

export function setFollowing(keycloak: Keycloak, profileId: string, following: boolean): Promise<void> {
  return request(keycloak, following ? "PUT" : "DELETE", PROFILES_API_URL, `/profiles/me/following/${encodeURIComponent(profileId)}`);
}

// Shared

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

async function orNullIfNotFound<T>(response: Promise<T>): Promise<T | null> {
  try {
    return await response;
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

/** Calls an API as the logged-in user, refreshing the access token first if it's about to expire. */
async function request<T>(
  keycloak: Keycloak,
  method: "GET" | "PUT" | "DELETE",
  baseUrl: string,
  path: string,
  { params = {}, body }: { params?: Record<string, string | number | undefined>; body?: unknown } = {},
): Promise<T> {
  await keycloak.updateToken(30);
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) query.set(key, String(value));
  }
  const response = await fetch(`${baseUrl}${path}${query.size ? `?${query}` : ""}`, {
    method,
    headers: {
      Authorization: `Bearer ${keycloak.token}`,
      ...(body !== undefined && { "Content-Type": "application/json" }),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    // The profiles API explains its errors as { "error": "..." }.
    const detail = await response.json().then((json) => json?.error).catch(() => undefined);
    throw new ApiError(response.status, detail ?? `The API answered ${response.status} ${response.statusText}`);
  }
  return response.status === 204 ? (undefined as T) : response.json();
}

export function posterUrl(posterPath: string, width: "w342" | "w500" = "w342"): string {
  return `https://image.tmdb.org/t/p/${width}${posterPath}`;
}
