"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useState } from "react";
import { searchProfiles } from "@/lib/api";
import { useKeycloak } from "../auth-gate";
import UserList from "./user-list";

/** All users, or those whose username starts with the search (?q=...), by username. */
export default function UsersBrowser() {
  const keycloak = useKeycloak();
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const query = searchParams.get("q")?.trim() ?? "";
  const [text, setText] = useState(query);

  const fetchPage = useCallback(
    (page: number) => searchProfiles(keycloak, query, { page, size: 30 }),
    [keycloak, query],
  );

  return (
    <main className="flex-1 px-6 py-6">
      <form
        role="search"
        className="mb-6 max-w-xl"
        onSubmit={(event) => {
          event.preventDefault();
          const q = text.trim();
          router.replace(q ? `${pathname}?q=${encodeURIComponent(q)}` : pathname, { scroll: false });
        }}
      >
        <input
          type="search"
          value={text}
          onChange={(event) => setText(event.target.value)}
          placeholder="Search users by username, then press Enter"
          aria-label="Search users"
          maxLength={100}
          className="w-full rounded-md border border-foreground/20 bg-background px-4 py-2"
        />
      </form>
      {/* A new search starts a fresh list. */}
      <UserList key={query} fetchPage={fetchPage} empty={query ? `No users starting with “${query}”.` : "No users yet."} />
    </main>
  );
}
