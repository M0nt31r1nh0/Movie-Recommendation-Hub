"use client";

import Image from "next/image";
import Link from "next/link";
import { useState } from "react";
import { posterUrl, type Movie, type RecommendedMovie } from "@/lib/api";

export default function MovieCard({ movie, className }: { movie: RecommendedMovie; className?: string }) {
  return (
    <li className={className}>
      <Link href={`/movies/${movie.id}`} className="group flex flex-col gap-2">
        <Poster movie={movie} sizes="200px" className="transition-opacity group-hover:opacity-80" />
        <div>
          <h2 className="text-sm font-medium leading-snug group-hover:underline">{movie.title}</h2>
          <p className="text-xs text-foreground/60">
            {movie.releaseDate ?? "No release date"}
            {/* TMDB stores 0 for movies nobody has rated yet. */}
            {!!movie.rating && ` · ★ ${movie.rating.toFixed(1)}`}
          </p>
        </div>
      </Link>
    </li>
  );
}

/** A 2:3 poster, or a "No poster" placeholder; TMDB no longer has some of the dataset's posters. */
export function Poster({
  movie,
  sizes,
  width = "w342",
  className = "",
}: {
  movie: Pick<Movie, "title" | "posterPath">;
  sizes: string;
  width?: "w342" | "w500";
  className?: string;
}) {
  const [missing, setMissing] = useState(!movie.posterPath);
  return (
    <div
      className={`relative flex aspect-[2/3] items-center justify-center overflow-hidden rounded-md bg-foreground/10 ${className}`}
    >
      {missing || !movie.posterPath ? (
        <span className="text-xs text-foreground/50">No poster</span>
      ) : (
        <Image
          src={posterUrl(movie.posterPath, width)}
          alt={`Poster of ${movie.title}`}
          fill
          sizes={sizes}
          className="object-cover"
          onError={() => setMissing(true)}
        />
      )}
    </div>
  );
}
