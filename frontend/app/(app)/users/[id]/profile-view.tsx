"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import {
  getProfile,
  listFollowers,
  listFollowing,
  listGenres,
  listWatched,
  listWatchlist,
  setFavoriteGenres,
  setFollowing,
  type Genre,
  type Profile,
} from "@/lib/api";
import { useInfinitePages } from "@/lib/use-infinite-pages";
import { useKeycloak } from "../../auth-gate";
import { Poster } from "../../movies/movie-card";
import UserList, { Avatar } from "../user-list";

type State =
  | { status: "loading" }
  | { status: "not-found" }
  | { status: "error"; message: string }
  | { status: "loaded"; profile: Profile };

type Tab = "watched" | "watchlist" | "following" | "followers";

/** A user's profile. `id` is a profile id, or "me" for the logged-in user. */
export default function ProfileView({ id }: { id: string }) {
  const keycloak = useKeycloak();
  const [state, setState] = useState<State>({ status: "loading" });
  const [tab, setTab] = useState<Tab>("watched");

  const reload = useCallback(
    () =>
      getProfile(keycloak, id)
        .then((profile) => setState(profile ? { status: "loaded", profile } : { status: "not-found" }))
        .catch((error: unknown) =>
          setState({ status: "error", message: error instanceof Error ? error.message : "Something went wrong" }),
        ),
    [keycloak, id],
  );
  useEffect(() => {
    void reload();
  }, [reload]);

  if (state.status !== "loaded") {
    return (
      <main className="flex-1 px-6 py-6">
        {state.status === "loading" && <p className="text-foreground/70">Loading…</p>}
        {state.status === "not-found" && <p>There&apos;s no user with id {id}.</p>}
        {state.status === "error" && <p className="text-red-600">Couldn&apos;t load the profile: {state.message}</p>}
      </main>
    );
  }

  const { profile } = state;
  const tabs: { key: Tab; label: string }[] = [
    { key: "watched", label: `Watched · ${profile.watchedCount}` },
    // Only on your own profile: nobody else can see your watchlist.
    ...(profile.watchlistCount !== null ? [{ key: "watchlist" as const, label: `Watch later · ${profile.watchlistCount}` }] : []),
    { key: "following", label: `Following · ${profile.followingCount}` },
    { key: "followers", label: `Followers · ${profile.followersCount}` },
  ];

  return (
    <main className="flex-1 px-6 py-6">
      <title>{`${profile.username} · Movies`}</title>
      <section className="flex flex-wrap items-center gap-4">
        <Avatar username={profile.username} large />
        <div className="flex-1">
          <h1 className="text-2xl font-semibold">
            {profile.username}
            {profile.me && <span className="ml-2 text-sm font-normal text-foreground/60">(you)</span>}
          </h1>
          <p className="text-sm text-foreground/60">Joined {formatDate(profile.createdAt)}</p>
        </div>
        {!profile.me && <FollowButton profile={profile} onChange={reload} />}
      </section>

      <FavoriteGenres profile={profile} onChange={(updated) => setState({ status: "loaded", profile: updated })} />

      <div role="tablist" className="mt-8 flex gap-1 border-b border-foreground/10">
        {tabs.map(({ key, label }) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={tab === key}
            onClick={() => setTab(key)}
            className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
              tab === key ? "border-foreground" : "border-transparent text-foreground/60 hover:text-foreground"
            }`}
          >
            {label}
          </button>
        ))}
      </div>
      <div className="pt-4">
        {tab === "watched" && <WatchedMovies key={profile.id} profileId={profile.id} me={profile.me} />}
        {tab === "watchlist" && <Watchlist />}
        {tab === "following" && <Following key={profile.id} profileId={profile.id} me={profile.me} />}
        {tab === "followers" && <Followers key={profile.id} profileId={profile.id} me={profile.me} />}
      </div>
    </main>
  );
}

function FollowButton({ profile, onChange }: { profile: Profile; onChange: () => Promise<void> }) {
  const keycloak = useKeycloak();
  const [busy, setBusy] = useState(false);
  const toggle = async () => {
    setBusy(true);
    try {
      await setFollowing(keycloak, profile.id, !profile.followedByMe);
      await onChange();
    } finally {
      setBusy(false);
    }
  };
  return (
    <button
      type="button"
      onClick={toggle}
      disabled={busy}
      className={`rounded-md px-4 py-2 text-sm font-medium disabled:opacity-50 ${
        profile.followedByMe ? "border border-foreground/20 hover:bg-foreground/10" : "bg-foreground text-background hover:opacity-85"
      }`}
    >
      {profile.followedByMe ? "Following ✓" : "Follow"}
    </button>
  );
}

/** The profile's favourite genres; on your own profile, with an editor listing every genre. */
function FavoriteGenres({ profile, onChange }: { profile: Profile; onChange: (profile: Profile) => void }) {
  const keycloak = useKeycloak();
  const [allGenres, setAllGenres] = useState<Genre[] | null>(null);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const editing = allGenres !== null;

  const startEditing = async () => {
    setError(null);
    setSelected(new Set(profile.favoriteGenres.map((genre) => genre.id)));
    try {
      setAllGenres(await listGenres(keycloak));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't load the genres");
    }
  };
  const toggle = (genreId: number) =>
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(genreId)) next.delete(genreId);
      else next.add(genreId);
      return next;
    });
  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      onChange(await setFavoriteGenres(keycloak, [...selected]));
      setAllGenres(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't save");
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="mt-8">
      <div className="mb-3 flex items-center gap-3">
        <h2 className="font-semibold">Favourite genres</h2>
        {profile.me && !editing && (
          <button type="button" onClick={startEditing} className="text-sm text-foreground/70 hover:underline">
            Edit
          </button>
        )}
      </div>
      {editing ? (
        <div className="flex flex-col gap-4">
          <div className="flex flex-wrap gap-2">
            {allGenres.map((genre) => (
              <label
                key={genre.id}
                className={`cursor-pointer rounded-full border px-3 py-1 text-sm ${
                  selected.has(genre.id) ? "border-foreground bg-foreground text-background" : "border-foreground/20"
                }`}
              >
                <input type="checkbox" checked={selected.has(genre.id)} onChange={() => toggle(genre.id)} className="sr-only" />
                {genre.name}
              </label>
            ))}
          </div>
          <div className="flex gap-2 text-sm">
            <button
              type="button"
              onClick={save}
              disabled={saving}
              className="rounded-md bg-foreground px-4 py-2 font-medium text-background hover:opacity-85 disabled:opacity-50"
            >
              Save
            </button>
            <button type="button" onClick={() => setAllGenres(null)} className="rounded-md px-4 py-2 font-medium hover:bg-foreground/10">
              Cancel
            </button>
          </div>
        </div>
      ) : profile.favoriteGenres.length > 0 ? (
        <ul className="flex flex-wrap gap-2">
          {profile.favoriteGenres.map((genre) => (
            <li key={genre.id} className="rounded-full bg-foreground/10 px-3 py-1 text-sm">
              {genre.name}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-foreground/60">
          {profile.me ? "You haven't picked any yet." : "None picked yet."}
        </p>
      )}
      {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
    </section>
  );
}

function WatchedMovies({ profileId, me }: { profileId: string; me: boolean }) {
  const keycloak = useKeycloak();
  const fetchPage = useCallback((page: number) => listWatched(keycloak, profileId, { page, size: 24 }), [keycloak, profileId]);
  const { items: movies, total, loading, error, sentinelRef } = useInfinitePages(fetchPage);
  return (
    <>
      <ul className="grid grid-cols-[repeat(auto-fill,minmax(140px,1fr))] gap-6">
        {movies.map((movie) => (
          <li key={movie.movieId}>
            <Link href={`/movies/${movie.movieId}`} className="group flex flex-col gap-2">
              <Poster movie={movie} sizes="180px" className="transition-opacity group-hover:opacity-80" />
              <div>
                <h3 className="text-sm font-medium leading-snug group-hover:underline">{movie.title}</h3>
                <p className="text-xs text-foreground/60">Watched {formatDate(movie.watchedAt)}</p>
              </div>
            </Link>
          </li>
        ))}
      </ul>
      <div ref={sentinelRef} className="h-px" />
      {loading && <p className="py-6 text-center text-sm text-foreground/60">Loading…</p>}
      {error && <p className="py-6 text-red-600">Couldn&apos;t load watched movies: {error}</p>}
      {!loading && !error && total === 0 && (
        <p className="text-foreground/70">
          {me ? "Nothing yet: use “Mark as watched” on a movie's page." : "No watched movies yet."}
        </p>
      )}
    </>
  );
}

/** Your own watch-later list; only you can see it. */
function Watchlist() {
  const keycloak = useKeycloak();
  const fetchPage = useCallback((page: number) => listWatchlist(keycloak, { page, size: 24 }), [keycloak]);
  const { items: movies, total, loading, error, sentinelRef } = useInfinitePages(fetchPage);
  return (
    <>
      <p className="mb-4 text-sm text-foreground/60">Only you can see this list. Movies leave it when you mark them as watched.</p>
      <ul className="grid grid-cols-[repeat(auto-fill,minmax(140px,1fr))] gap-6">
        {movies.map((movie) => (
          <li key={movie.movieId}>
            <Link href={`/movies/${movie.movieId}`} className="group flex flex-col gap-2">
              <Poster movie={movie} sizes="180px" className="transition-opacity group-hover:opacity-80" />
              <div>
                <h3 className="text-sm font-medium leading-snug group-hover:underline">{movie.title}</h3>
                <p className="text-xs text-foreground/60">Added {formatDate(movie.addedAt)}</p>
              </div>
            </Link>
          </li>
        ))}
      </ul>
      <div ref={sentinelRef} className="h-px" />
      {loading && <p className="py-6 text-center text-sm text-foreground/60">Loading…</p>}
      {error && <p className="py-6 text-red-600">Couldn&apos;t load your watchlist: {error}</p>}
      {!loading && !error && total === 0 && (
        <p className="text-foreground/70">Nothing yet: use “Watch later” on a movie&apos;s page.</p>
      )}
    </>
  );
}

function Following({ profileId, me }: { profileId: string; me: boolean }) {
  const keycloak = useKeycloak();
  const fetchPage = useCallback((page: number) => listFollowing(keycloak, profileId, { page, size: 30 }), [keycloak, profileId]);
  return <UserList fetchPage={fetchPage} empty={me ? "You don't follow anyone yet." : "Doesn't follow anyone yet."} />;
}

function Followers({ profileId, me }: { profileId: string; me: boolean }) {
  const keycloak = useKeycloak();
  const fetchPage = useCallback((page: number) => listFollowers(keycloak, profileId, { page, size: 30 }), [keycloak, profileId]);
  return <UserList fetchPage={fetchPage} empty={me ? "Nobody follows you yet." : "No followers yet."} />;
}

/** An ISO date or timestamp -> "14 March 1972" (in the browser's language). */
function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { dateStyle: "long", timeZone: "UTC" });
}
