"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { getMovie, type Movie } from "@/lib/api";
import { useKeycloak } from "../auth-gate";
import { Poster } from "../movie-card";

type State = { status: "loading" } | { status: "not-found" } | { status: "error"; message: string } | { status: "loaded"; movie: Movie };

export default function MovieDetails({ id }: { id: string }) {
  const keycloak = useKeycloak();
  const [state, setState] = useState<State>({ status: "loading" });

  useEffect(() => {
    getMovie(keycloak, id)
      .then((movie) => setState(movie ? { status: "loaded", movie } : { status: "not-found" }))
      .catch((error: unknown) =>
        setState({ status: "error", message: error instanceof Error ? error.message : "Something went wrong" }),
      );
  }, [keycloak, id]);

  return (
    <main className="flex-1 px-6 py-6">
      <Link href="/movies" className="text-sm text-foreground/70 hover:underline">
        ← All movies
      </Link>
      {state.status === "loading" && <p className="mt-6 text-foreground/70">Loading…</p>}
      {state.status === "not-found" && <p className="mt-6">There&apos;s no movie with id {id}.</p>}
      {state.status === "error" && <p className="mt-6 text-red-600">Couldn&apos;t load the movie: {state.message}</p>}
      {state.status === "loaded" && <MovieInfo movie={state.movie} />}
    </main>
  );
}

function MovieInfo({ movie }: { movie: Movie }) {
  return (
    <article className="mt-6 flex max-w-4xl flex-col gap-8 sm:flex-row">
      <title>{`${movie.title} · Movies`}</title>
      <Poster movie={movie} width="w500" sizes="(min-width: 640px) 300px, 100vw" className="w-full shrink-0 sm:w-72" />
      <div className="flex flex-col gap-4">
        <div>
          <h1 className="text-3xl font-semibold">{movie.title}</h1>
          <p className="mt-1 text-foreground/70">
            {movie.releaseDate ? formatDate(movie.releaseDate) : "No release date"}
          </p>
        </div>

        <p className="text-lg">
          {movie.rating && movie.ratingCount ? (
            <>
              ★ {movie.rating.toFixed(1)}
              <span className="text-foreground/60"> / 10 · {movie.ratingCount.toLocaleString()} votes</span>
            </>
          ) : (
            <span className="text-foreground/60">Not rated yet</span>
          )}
        </p>

        {movie.genres.length > 0 && (
          <ul className="flex flex-wrap gap-2">
            {movie.genres.map((genre) => (
              <li key={genre.id} className="rounded-full bg-foreground/10 px-3 py-1 text-sm">
                {genre.name}
              </li>
            ))}
          </ul>
        )}

        <p className="leading-relaxed">{movie.description ?? "No description available."}</p>

        <div className="flex flex-wrap gap-3 text-sm">
          {movie.imdbId && (
            <ExternalLink href={`https://www.imdb.com/title/${movie.imdbId}/`}>IMDb</ExternalLink>
          )}
          <ExternalLink href={`https://www.themoviedb.org/movie/${movie.id}`}>TMDB</ExternalLink>
        </div>
      </div>
    </article>
  );
}

function ExternalLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="rounded-md border border-foreground/20 px-4 py-2 font-medium hover:bg-foreground/10"
    >
      {children} ↗
    </a>
  );
}

/** "1972-03-14" -> "14 March 1972" (in the browser's language). */
function formatDate(isoDate: string): string {
  return new Date(isoDate).toLocaleDateString(undefined, { dateStyle: "long", timeZone: "UTC" });
}
