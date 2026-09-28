import MovieDetails from "./movie-details";

export default async function MoviePage({ params }: PageProps<"/movies/[id]">) {
  const { id } = await params;
  return <MovieDetails id={id} />;
}
