import type { Metadata } from "next";
import { Suspense } from "react";
import UsersBrowser from "./users-browser";

export const metadata: Metadata = {
  title: "Users · Movies",
};

export default function UsersPage() {
  // UsersBrowser reads the search from the URL (useSearchParams), which needs a Suspense boundary.
  return (
    <Suspense>
      <UsersBrowser />
    </Suspense>
  );
}
