import type { NextConfig } from "next";

const nextConfig: NextConfig = {
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
