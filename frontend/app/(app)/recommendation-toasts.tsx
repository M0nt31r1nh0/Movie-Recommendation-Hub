"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { subscribeToRecommendationEvents } from "@/lib/recommendation-events";

type Toast = { requestId: string; failed: boolean; error: string | null };

const SHOW_DONE_FOR_MS = 15_000;

/**
 * Tells the user when a request they typed on Ask AI is ready, or failed and why, wherever they are.
 * Not on Ask AI itself, which shows it; "Recommended for you" updates itself.
 */
export default function RecommendationToasts() {
  const pathname = usePathname();
  const [toasts, setToasts] = useState<Toast[]>([]);
  // A ref, so the subscription doesn't restart on every navigation.
  const onAskAi = useRef(false);
  useEffect(() => {
    onAskAi.current = pathname.startsWith("/recommendations");
  }, [pathname]);

  const dismiss = (requestId: string) => setToasts((shown) => shown.filter((t) => t.requestId !== requestId));

  useEffect(
    () =>
      subscribeToRecommendationEvents((event) => {
        if (event.type !== "ready" || event.kind !== "PROMPT" || onAskAi.current) return;
        const toast = { requestId: event.requestId, failed: event.status === "FAILED", error: event.error };
        setToasts((shown) => [...shown.filter((t) => t.requestId !== toast.requestId), toast]);
        // Failures stay until dismissed, so they aren't missed.
        if (!toast.failed) setTimeout(() => dismiss(toast.requestId), SHOW_DONE_FOR_MS);
      }),
    [],
  );

  if (toasts.length === 0) return null;
  return (
    <ul aria-live="polite" className="fixed right-4 bottom-4 z-50 flex w-80 flex-col gap-2">
      {toasts.map((toast) => (
        <li
          key={toast.requestId}
          role={toast.failed ? "alert" : "status"}
          className={`rounded-lg border bg-background px-4 py-3 text-sm shadow-lg ${
            toast.failed ? "border-red-600/40" : "border-foreground/15"
          }`}
        >
          <p className={toast.failed ? "text-red-600" : undefined}>
            {toast.failed
              ? `Your Ask AI request failed: ${toast.error ?? "unknown error"}`
              : "Your Ask AI recommendations are ready."}
          </p>
          <div className="mt-2 flex gap-4">
            <Link
              href={toast.failed ? "/recommendations" : `/recommendations?request=${toast.requestId}`}
              onClick={() => dismiss(toast.requestId)}
              className="font-medium hover:underline"
            >
              {toast.failed ? "Try again" : "See them"}
            </Link>
            <button type="button" onClick={() => dismiss(toast.requestId)} className="text-foreground/60 hover:underline">
              Dismiss
            </button>
          </div>
        </li>
      ))}
    </ul>
  );
}
