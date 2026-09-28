import AuthButtons from "./auth-buttons";

export default function Home() {
  return (
    <>
      <header className="flex h-16 items-center justify-end border-b border-foreground/10 px-6">
        <AuthButtons />
      </header>
      <main className="flex-1" />
    </>
  );
}
