import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // `next build` also writes a self-contained server to .next/standalone, which the Docker image runs
  // (see Dockerfile). `npm run dev` and `npm start` are unaffected.
  output: "standalone",
  images: {
    // Movie posters come from TMDB; posterPath in the API is relative to this host.
    remotePatterns: [
      {
        protocol: "https",
        hostname: "image.tmdb.org",
        port: "",
        pathname: "/t/p/**",
        search: "",
      },
    ],
  },
};

export default nextConfig;
