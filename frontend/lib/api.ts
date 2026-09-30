import type Keycloak from "keycloak-js";

const MOVIES_API_URL = process.env.NEXT_PUBLIC_MOVIES_API_URL ?? "http://localhost:8080";
const PROFILES_API_URL = process.env.NEXT_PUBLIC_PROFILES_API_URL ?? "http://localhost:8081";
export const RECOMMENDATIONS_API_URL = process.env.NEXT_PUBLIC_RECOMMENDATIONS_API_URL ?? "http://localhost:8082";

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
  /** Only on your own profile (null on others): only its owner sees a watchlist. */
  watchlistCount: number | null;
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

export type WatchlistEntry = {
  movieId: number;
  title: string;
  posterPath: string | null;
  releaseDate: string | null;
  addedAt: string;
};

/** A recommended movie: just what a card needs. */
export type RecommendedMovie = Pick<Movie, "id" | "title" | "posterPath" | "releaseDate" | "rating">;

export type MyRecommendations = {
  /** READY: `movies` are there. PREPARING: the first set is being made. FAILED: see `error`. */
  status: "READY" | "PREPARING" | "FAILED";
  requestId: string | null;
  generatedAt: string | null;
  /** Whether a newer set is being made while these are shown. */
  refreshing: boolean;
  error: string | null;
  movies: RecommendedMovie[];
};

export type RecommendationRequest = {
  id: string;
  kind: "PROFILE" | "PROMPT";
  prompt: string | null;
  status: "PENDING" | "DONE" | "FAILED";
  error: string | null;
  createdAt: string;
  completedAt: string | null;
  model: string | null;
  /** Only from getRecommendationRequest, once it's DONE. */
  movies: RecommendedMovie[];
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

/** Marks or unmarks a movie as seen. Marking it also takes it off the watchlist. */
export function setWatched(keycloak: Keycloak, movieId: number, watched: boolean): Promise<void> {
  return request(keycloak, watched ? "PUT" : "DELETE", PROFILES_API_URL, `/profiles/me/watched/${movieId}`);
}

/** The logged-in user's watch-later list, most recently added first. */
export function listWatchlist(keycloak: Keycloak, options: PageOptions = {}): Promise<Page<WatchlistEntry>> {
  return request(keycloak, "GET", PROFILES_API_URL, "/profiles/me/watchlist", { params: options });
}

/** Whether the movie is on the logged-in user's watchlist. */
export async function isOnWatchlist(keycloak: Keycloak, movieId: number): Promise<boolean> {
  return (await orNullIfNotFound(request(keycloak, "GET", PROFILES_API_URL, `/profiles/me/watchlist/${movieId}`))) !== null;
}

export function setOnWatchlist(keycloak: Keycloak, movieId: number, onWatchlist: boolean): Promise<void> {
  return request(keycloak, onWatchlist ? "PUT" : "DELETE", PROFILES_API_URL, `/profiles/me/watchlist/${movieId}`);
}

export function setFollowing(keycloak: Keycloak, profileId: string, following: boolean): Promise<void> {
  return request(keycloak, following ? "PUT" : "DELETE", PROFILES_API_URL, `/profiles/me/following/${encodeURIComponent(profileId)}`);
}

// recommendation-service. Requests take minutes: their results arrive as events (see recommendation-events.ts).

/**
 * The logged-in user's "Recommended for you"; the first call starts making them. After that, they're
 * remade in the background whenever the profile, or what the people it follows watched, changes.
 */
export function getMyRecommendations(keycloak: Keycloak): Promise<MyRecommendations> {
  return request(keycloak, "GET", RECOMMENDATIONS_API_URL, "/recommendations/me");
}

/**
 * Asks for movies matching `prompt`, e.g. "a horror movie from the 80s": usually PENDING, or DONE at once
 * if the same question was answered for the same profile. One at a time: ApiError 409 (with the
 * `requestId` being prepared) while another is.
 */
export function askForRecommendations(keycloak: Keycloak, prompt: string): Promise<RecommendationRequest> {
  return request(keycloak, "POST", RECOMMENDATIONS_API_URL, "/recommendations/requests", { body: { prompt } });
}

/** The request being prepared (if any), then the last five answers, newest first, without their movies. */
export function listRecommendationRequests(keycloak: Keycloak): Promise<RecommendationRequest[]> {
  return request(keycloak, "GET", RECOMMENDATIONS_API_URL, "/recommendations/requests");
}

/** The typed request being prepared, or else one that failed in the last hour, or null. */
export async function getCurrentRecommendationRequest(keycloak: Keycloak): Promise<RecommendationRequest | null> {
  return (await request<RecommendationRequest | undefined>(keycloak, "GET", RECOMMENDATIONS_API_URL, "/recommendations/requests/current")) ?? null;
}

/** One of the user's requests, with its movies once it's DONE; null if there's no such request (any more). */
export function getRecommendationRequest(keycloak: Keycloak, id: string): Promise<RecommendationRequest | null> {
  return orNullIfNotFound(request(keycloak, "GET", RECOMMENDATIONS_API_URL, `/recommendations/requests/${encodeURIComponent(id)}`));
}

// Shared

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    /** The request an error is about, e.g. the one still being prepared on a 409. */
    readonly requestId?: string,
  ) {
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
  method: "GET" | "POST" | "PUT" | "DELETE",
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
    // The profiles and recommendations APIs explain their errors as { "error": "..." }.
    const json = await response.json().catch(() => undefined);
    throw new ApiError(response.status, json?.error ?? `The API answered ${response.status} ${response.statusText}`, json?.requestId);
  }
  return response.status === 204 ? (undefined as T) : response.json();
}

export function posterUrl(posterPath: string, width: "w342" | "w500" = "w342"): string {
  return `https://image.tmdb.org/t/p/${width}${posterPath}`;
}
