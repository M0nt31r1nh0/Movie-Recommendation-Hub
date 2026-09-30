import type { Metadata } from "next";
import { Suspense } from "react";
import AskAi from "./ask-ai";

export const metadata: Metadata = {
  title: "Ask AI · Movies",
};

export default function RecommendationsPage() {
  // AskAi reads the request being shown from the URL (useSearchParams), which needs a Suspense boundary.
  return (
    <Suspense>
      <AskAi />
    </Suspense>
  );
}
