import type { Metadata } from "next";
import { Suspense } from "react";
import MoviesBrowser from "./movies-browser";

export const metadata: Metadata = {
  title: "Movies",
};

export default function MoviesPage() {
  // MoviesBrowser reads the search and sort from the URL (useSearchParams), which needs a Suspense boundary.
  return (
    <Suspense>
      <MoviesBrowser />
    </Suspense>
  );
}
