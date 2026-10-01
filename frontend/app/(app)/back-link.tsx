"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { canGoBack } from "@/lib/app-history";

/**
 * "← Back": to the page the user came from in the app, just as they left it (its search, sort or
 * selected request), or to `fallback` if they opened this page directly.
 */
export default function BackLink({ fallback }: { fallback: string }) {
  const router = useRouter();
  return (
    <Link
      href={fallback}
      onClick={(event) => {
        if (!canGoBack()) return;
        event.preventDefault();
        router.back();
      }}
      className="text-sm text-foreground/70 hover:underline"
    >
      ← Back
    </Link>
  );
}
