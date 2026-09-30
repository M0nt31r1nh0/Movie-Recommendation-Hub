import type Keycloak from "keycloak-js";
import { RECOMMENDATIONS_API_URL } from "./api";

/** One of the user's requests finished: recommendation-service's "recommendations-ready" event. */
export type ReadyEvent = {
  requestId: string;
  kind: "PROFILE" | "PROMPT";
  status: "DONE" | "FAILED";
  /** Why it failed. */
  error: string | null;
};

/**
 * What subscribers hear: a finished request, or "reconnected" after the stream was closed for a while,
 * when events may have been missed, so anything still waiting for one should ask the API again.
 */
export type RecommendationEvent = ({ type: "ready" } & ReadyEvent) | { type: "reconnected" };

const listeners = new Set<(event: RecommendationEvent) => void>();

/** Calls `listener` with every event until the returned function is called. */
export function subscribeToRecommendationEvents(listener: (event: RecommendationEvent) => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

// The server checks the access token only when the stream opens, and tokens last five minutes, so
// reopen it with a fresh one a little before that: a logged-out user stops getting events.
const REOPEN_AFTER_MS = 4 * 60_000;
const LONGEST_RETRY_MS = 60_000;

/**
 * Keeps the logged-in user's event stream (GET /recommendations/events) open, reconnecting when it
 * drops, until the returned function is called. It uses fetch because EventSource can't send the
 * Authorization header.
 */
export function startRecommendationEvents(keycloak: Keycloak): () => void {
  const stopped = new AbortController();
  void keepOpen(keycloak, stopped.signal);
  return () => stopped.abort();
}

async function keepOpen(keycloak: Keycloak, stopped: AbortSignal) {
  let failures = 0;
  let connectedBefore = false;
  while (!stopped.aborted) {
    const connection = new AbortController();
    const close = () => connection.abort();
    stopped.addEventListener("abort", close);
    const timer = setTimeout(close, REOPEN_AFTER_MS);
    try {
      await keycloak.updateToken(30);
      const response = await fetch(`${RECOMMENDATIONS_API_URL}/recommendations/events`, {
        headers: { Authorization: `Bearer ${keycloak.token}`, Accept: "text/event-stream" },
        cache: "no-store",
        signal: connection.signal,
      });
      if (!response.ok || !response.body) throw new Error(`The events stream answered ${response.status}`);
      for await (const { event, data } of readEvents(response.body)) {
        if (event === "connected") {
          failures = 0;
          if (connectedBefore) emit({ type: "reconnected" });
          connectedBefore = true;
        } else if (event === "recommendations-ready") {
          try {
            emit({ type: "ready", ...(JSON.parse(data) as ReadyEvent) });
          } catch {
            // not JSON: ignore it
          }
        }
      }
      // The server closed the stream (a restart, say): reconnect, but not in a tight loop.
      failures += 1;
    } catch {
      if (!connection.signal.aborted) failures += 1; // aborted: time to reopen, or stopped
    } finally {
      clearTimeout(timer);
      stopped.removeEventListener("abort", close);
    }
    if (failures > 0) await sleep(Math.min(LONGEST_RETRY_MS, 1000 * 2 ** failures), stopped);
  }
}

function emit(event: RecommendationEvent) {
  for (const listener of listeners) listener(event);
}

/** The events in a text/event-stream body, as they arrive; comments (the keep-alives) are skipped. */
async function* readEvents(body: ReadableStream<Uint8Array>): AsyncGenerator<{ event: string; data: string }> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) return;
    buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, "\n");
    let end;
    while ((end = buffer.indexOf("\n\n")) >= 0) {
      const block = buffer.slice(0, end);
      buffer = buffer.slice(end + 2);
      let event = "message";
      const data: string[] = [];
      for (const line of block.split("\n")) {
        if (line === "" || line.startsWith(":")) continue;
        const colon = line.indexOf(":");
        const field = colon < 0 ? line : line.slice(0, colon);
        const value = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
        if (field === "event") event = value;
        else if (field === "data") data.push(value);
      }
      if (data.length > 0) yield { event, data: data.join("\n") };
    }
  }
}

function sleep(ms: number, stopped: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(done, ms);
    stopped.addEventListener("abort", done, { once: true });
    function done() {
      clearTimeout(timer);
      stopped.removeEventListener("abort", done);
      resolve();
    }
  });
}
