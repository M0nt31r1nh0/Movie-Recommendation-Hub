"use client";

import Link from "next/link";
import type { Page, ProfileSummary } from "@/lib/api";
import { useInfinitePages } from "@/lib/use-infinite-pages";
import { useKeycloak } from "../auth-gate";

/** An infinitely scrolling list of users, each linking to their profile. `fetchPage` should be memoised. */
export default function UserList({
  fetchPage,
  empty,
}: {
  fetchPage: (page: number) => Promise<Page<ProfileSummary>>;
  empty: string;
}) {
  const keycloak = useKeycloak();
  const { items: users, total, loading, error, sentinelRef } = useInfinitePages(fetchPage);
  return (
    <>
      <ul className="divide-y divide-foreground/10">
        {users.map((user) => (
          <li key={user.id}>
            <Link href={`/users/${user.id}`} className="group flex items-center gap-3 py-3">
              <Avatar username={user.username} />
              <span className="font-medium group-hover:underline">{user.username}</span>
              {user.id === keycloak.tokenParsed?.sub && <span className="text-xs text-foreground/60">you</span>}
            </Link>
          </li>
        ))}
      </ul>
      <div ref={sentinelRef} className="h-px" />
      {loading && <p className="py-6 text-center text-sm text-foreground/60">Loading…</p>}
      {error && <p className="py-6 text-red-600">Couldn&apos;t load users: {error}</p>}
      {!loading && !error && total === 0 && <p className="py-3 text-foreground/70">{empty}</p>}
    </>
  );
}

export function Avatar({ username, large = false }: { username: string; large?: boolean }) {
  return (
    <span
      aria-hidden
      className={`flex shrink-0 items-center justify-center rounded-full bg-foreground/10 font-semibold uppercase ${
        large ? "h-16 w-16 text-2xl" : "h-9 w-9 text-sm"
      }`}
    >
      {username.charAt(0)}
    </span>
  );
}
