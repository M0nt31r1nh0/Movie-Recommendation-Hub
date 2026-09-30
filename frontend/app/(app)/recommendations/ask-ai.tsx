"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import {
  ApiError,
  askForRecommendations,
  getCurrentRecommendationRequest,
  getRecommendationRequest,
  listRecommendationRequests,
  type RecommendationRequest,
} from "@/lib/api";
import { subscribeToRecommendationEvents } from "@/lib/recommendation-events";
import { timeAgo } from "@/lib/time-ago";
import { useKeycloak } from "../auth-gate";
import MovieCard from "../movies/movie-card";

const MAX_PROMPT = 300;
// The failure the user dismissed, so it stays dismissed after a reload (the server keeps it an hour).
const DISMISSED_KEY = "ask-ai.dismissed-failure";

/**
 * Ask AI: the user describes what they feel like watching, and the model picks movies they haven't
 * seen. One request at a time, which takes minutes; the answer arrives as an event. The last five
 * answers are kept, and the one shown is in the URL (?request=...), so a notification can link to it.
 */
export default function AskAi() {
  const keycloak = useKeycloak();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [history, setHistory] = useState<RecommendationRequest[] | null>(null);
  const [current, setCurrent] = useState<RecommendationRequest | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState<string | null>(() => readDismissed());
  // Bumped when a request finishes, so the one shown is fetched again.
  const [finished, setFinished] = useState(0);

  const load = useCallback(
    () =>
      Promise.all([listRecommendationRequests(keycloak), getCurrentRecommendationRequest(keycloak)])
        .then(([requests, now]) => {
          setHistory(requests);
          setCurrent(now);
          setLoadError(null);
        })
        .catch((error: unknown) => setLoadError(error instanceof Error ? error.message : "Something went wrong")),
    [keycloak],
  );

  useEffect(() => {
    void load();
  }, [load]);

  const select = useCallback(
    (id: string) => router.replace(`${pathname}?request=${encodeURIComponent(id)}`, { scroll: false }),
    [router, pathname],
  );

  const pending = current?.status === "PENDING" ? current : null;
  const failure = current?.status === "FAILED" && current.id !== dismissed ? current : null;

  useEffect(
    () =>
      subscribeToRecommendationEvents((event) => {
        if (event.type === "ready" && event.kind !== "PROMPT") return;
        if (event.type === "reconnected" && !pending) return;
        void load();
        setFinished((n) => n + 1);
        if (event.type === "ready" && event.status === "DONE") select(event.requestId);
      }),
    [load, pending, select],
  );

  // The request shown: the one in the URL, or else the newest answer, or else the one being prepared.
  const selectedId = searchParams.get("request") ?? history?.find((r) => r.status === "DONE")?.id ?? pending?.id ?? null;

  const dismiss = (id: string) => {
    setDismissed(id);
    try {
      localStorage.setItem(DISMISSED_KEY, id);
    } catch {
      // storage is blocked: it stays dismissed until a reload
    }
  };

  return (
    <main className="flex-1 px-6 py-6">
      <div className="mx-auto flex max-w-6xl flex-col gap-8 lg:flex-row">
        <aside className="flex flex-col gap-6 lg:w-80 lg:shrink-0">
          <AskForm
            pending={pending}
            onAsked={async (request) => {
              await load();
              select(request.id);
            }}
            onBusy={load}
          />
          {failure && (
            <div role="alert" className="rounded-md border border-red-600/30 bg-red-600/5 p-3 text-sm">
              <p>
                <span className="font-medium">Your request “{failure.prompt}” failed:</span> {failure.error}
              </p>
              <button type="button" onClick={() => dismiss(failure.id)} className="mt-2 text-foreground/60 hover:underline">
                Dismiss
              </button>
            </div>
          )}
          <History history={history} error={loadError} selectedId={selectedId} onSelect={select} />
        </aside>
        <section aria-live="polite" className="min-w-0 flex-1">
          {selectedId ? (
            <Answer key={`${selectedId}|${finished}`} id={selectedId} />
          ) : (
            history && (
              <p className="text-foreground/70">
                Your answers will appear here. The last five are kept, so you can come back to them.
              </p>
            )
          )}
        </section>
      </div>
    </main>
  );
}

function AskForm({
  pending,
  onAsked,
  onBusy,
}: {
  pending: RecommendationRequest | null;
  onAsked: (request: RecommendationRequest) => Promise<void>;
  /** Another request is being prepared (from another tab, say): find out which. */
  onBusy: () => Promise<void>;
}) {
  const keycloak = useKeycloak();
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const prompt = text.trim();
    if (!prompt || sending || pending) return;
    setSending(true);
    setError(null);
    try {
      const request = await askForRecommendations(keycloak, prompt);
      setText("");
      await onAsked(request);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) await onBusy();
      setError(e instanceof Error ? e.message : "Something went wrong");
    } finally {
      setSending(false);
    }
  };

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <h1 className="text-2xl font-semibold">Ask AI</h1>
      <p className="text-sm text-foreground/70">
        Describe what you feel like watching, and get movies you haven&apos;t seen or saved yet. For example: “a
        horror movie from the 80s” or “feel-good comedies about friendship”.
      </p>
      <textarea
        value={text}
        onChange={(event) => setText(event.target.value)}
        onKeyDown={(event) => {
          // Enter asks; Shift+Enter adds a line.
          if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            event.currentTarget.form?.requestSubmit();
          }
        }}
        rows={3}
        maxLength={MAX_PROMPT}
        disabled={!!pending || sending}
        placeholder="What would you like to watch?"
        aria-label="What would you like to watch?"
        className="w-full resize-none rounded-md border border-foreground/20 bg-background px-3 py-2 disabled:opacity-60"
      />
      <div className="flex items-center justify-between">
        <span className="text-xs text-foreground/50">
          {text.length}/{MAX_PROMPT}
        </span>
        <button
          type="submit"
          disabled={!text.trim() || !!pending || sending}
          className="rounded-md bg-foreground px-4 py-2 text-sm font-medium text-background hover:opacity-90 disabled:opacity-40"
        >
          {sending ? "Asking…" : "Ask"}
        </button>
      </div>
      {pending && (
        <p className="text-sm text-foreground/70">
          Preparing your answer to “{pending.prompt}”. This can take a few minutes; you can leave this page and
          you&apos;ll be told when it&apos;s ready.
        </p>
      )}
      {error && !pending && <p className="text-sm text-red-600">{error}</p>}
    </form>
  );
}

function History({
  history,
  error,
  selectedId,
  onSelect,
}: {
  history: RecommendationRequest[] | null;
  error: string | null;
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  return (
    <nav aria-labelledby="history-heading">
      <h2 id="history-heading" className="mb-2 text-sm font-semibold text-foreground/70">
        Your last requests
      </h2>
      {error && <p className="text-sm text-red-600">Couldn&apos;t load them: {error}</p>}
      {!error && history === null && <p className="text-sm text-foreground/60">Loading…</p>}
      {history?.length === 0 && <p className="text-sm text-foreground/60">None yet.</p>}
      <ul className="flex flex-col gap-1">
        {history?.map((request) => (
          <li key={request.id}>
            <button
              type="button"
              onClick={() => onSelect(request.id)}
              aria-current={request.id === selectedId ? "true" : undefined}
              className="flex w-full flex-col rounded-md px-3 py-2 text-left hover:bg-foreground/5 aria-[current=true]:bg-foreground/10"
            >
              <span className="line-clamp-2 text-sm">{request.prompt}</span>
              <span className="text-xs text-foreground/60">
                {request.status === "PENDING" ? "Preparing…" : timeAgo(request.completedAt ?? request.createdAt)}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </nav>
  );
}

type AnswerState =
  | { status: "loading" }
  | { status: "gone" }
  | { status: "error"; message: string }
  | { status: "loaded"; request: RecommendationRequest };

/** One request: its movies once it's answered. */
function Answer({ id }: { id: string }) {
  const keycloak = useKeycloak();
  const [state, setState] = useState<AnswerState>({ status: "loading" });

  useEffect(() => {
    getRecommendationRequest(keycloak, id)
      .then((request) => setState(request ? { status: "loaded", request } : { status: "gone" }))
      .catch((error: unknown) =>
        setState({ status: "error", message: error instanceof Error ? error.message : "Something went wrong" }),
      );
  }, [keycloak, id]);

  if (state.status === "loading") return <p className="text-foreground/70">Loading…</p>;
  if (state.status === "gone") return <p className="text-foreground/70">This request is no longer kept: only the last five are.</p>;
  if (state.status === "error") return <p className="text-red-600">Couldn&apos;t load it: {state.message}</p>;

  const { request } = state;
  return (
    <>
      <h2 className="text-xl font-semibold">“{request.prompt}”</h2>
      <p className="mt-1 mb-6 text-sm text-foreground/60">
        {request.status === "PENDING" && "Being prepared. This can take a few minutes."}
        {request.status === "FAILED" && <span className="text-red-600">Failed: {request.error}</span>}
        {request.status === "DONE" && request.completedAt && `Answered ${timeAgo(request.completedAt)}${request.model ? ` by ${request.model}` : ""}`}
      </p>
      {request.status === "DONE" &&
        (request.movies.length > 0 ? (
          <ul className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-6">
            {request.movies.map((movie) => (
              <MovieCard key={movie.id} movie={movie} />
            ))}
          </ul>
        ) : (
          <p className="text-foreground/70">No movies matched. Try describing it differently.</p>
        ))}
    </>
  );
}

function readDismissed(): string | null {
  try {
    return localStorage.getItem(DISMISSED_KEY);
  } catch {
    return null;
  }
}
