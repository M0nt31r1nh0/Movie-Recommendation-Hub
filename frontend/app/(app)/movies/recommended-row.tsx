"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { getMyRecommendations, type MyRecommendations } from "@/lib/api";
import { subscribeToRecommendationEvents } from "@/lib/recommendation-events";
import { timeAgo } from "@/lib/time-ago";
import { useKeycloak } from "../auth-gate";
import MovieCard from "./movie-card";

type State = { status: "loading" } | { status: "unavailable"; message: string } | { status: "loaded"; mine: MyRecommendations };

/**
 * "Recommended for you": movies picked for the user from their favourite genres, what they watched
 * and saved, and what the people they follow watched. The first set takes a few minutes; later ones
 * are made in the background whenever any of that changes, and the row updates itself when they're ready.
 */
export default function RecommendedRow() {
  const keycloak = useKeycloak();
  const [state, setState] = useState<State>({ status: "loading" });

  const load = useCallback(
    () =>
      getMyRecommendations(keycloak)
        .then((mine) => setState({ status: "loaded", mine }))
        .catch((error: unknown) =>
          setState({ status: "unavailable", message: error instanceof Error ? error.message : "Something went wrong" }),
        ),
    [keycloak],
  );

  useEffect(() => {
    void load();
  }, [load]);

  const waiting = state.status === "loaded" && (state.mine.status === "PREPARING" || state.mine.refreshing);

  // A finished set (or a failed attempt) replaces what's shown; after the stream was down, check.
  useEffect(
    () =>
      subscribeToRecommendationEvents((event) => {
        if (event.type === "ready" ? event.kind === "PROFILE" : waiting) void load();
      }),
    [load, waiting],
  );

  const mine = state.status === "loaded" ? state.mine : null;
  return (
    <section aria-labelledby="recommended-heading" className="mb-10">
      <div className="mb-3 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2 id="recommended-heading" className="text-lg font-semibold">
          Recommended for you
        </h2>
        {mine?.status === "READY" && mine.generatedAt && (
          <span className="text-sm text-foreground/60">
            Picked {timeAgo(mine.generatedAt)}
            {mine.refreshing && " · updating…"}
          </span>
        )}
      </div>

      {state.status === "loading" && <Placeholders />}
      {state.status === "unavailable" && (
        <p className="text-sm text-foreground/60">Recommendations are unavailable right now ({state.message}).</p>
      )}
      {mine?.status === "PREPARING" && (
        <>
          <p className="mb-3 text-sm text-foreground/70">
            Picking movies for you. The first time takes a few minutes; they&apos;ll appear here when they&apos;re ready.
          </p>
          <Placeholders />
        </>
      )}
      {mine?.status === "FAILED" && (
        <p className="text-sm text-red-600">
          Couldn&apos;t pick movies for you: {mine.error} They&apos;ll be tried again automatically.
        </p>
      )}
      {mine?.status === "READY" &&
        (mine.movies.length > 0 ? (
          <ul className="flex gap-4 overflow-x-auto pb-3">
            {mine.movies.map((movie) => (
              <MovieCard key={movie.id} movie={movie} className="w-36 shrink-0" />
            ))}
          </ul>
        ) : (
          <p className="text-sm text-foreground/70">
            Nothing to recommend yet. Pick favourite genres on <Link href="/users/me" className="underline">your profile</Link>,
            or mark some movies as watched.
          </p>
        ))}
    </section>
  );
}

function Placeholders() {
  return (
    <ul aria-hidden className="flex gap-4 overflow-hidden pb-3">
      {Array.from({ length: 8 }, (_, i) => (
        <li key={i} className="aspect-[2/3] w-36 shrink-0 animate-pulse rounded-md bg-foreground/10" />
      ))}
    </ul>
  );
}
