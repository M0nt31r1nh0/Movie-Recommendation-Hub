import AuthButtons from "./auth-buttons";
import MoviesLogo from "./movies-logo";

export default function Home() {
  return (
    <>
      <header className="flex h-16 items-center justify-end border-b border-foreground/10 px-6">
        <AuthButtons />
      </header>
      <main className="flex flex-1 items-center justify-center p-6">
        <MoviesLogo className="h-48 w-48 sm:h-72 sm:w-72" />
      </main>
      <footer className="border-t border-foreground/10 px-6 py-4 text-center text-sm text-foreground/60">
        <span className="whitespace-nowrap">© 2026</span> ·{" "}
        <span className="whitespace-nowrap">João Russo (116543)</span> ·{" "}
        <span className="whitespace-nowrap">Manuel Fernandes (106410)</span> ·{" "}
        <span className="whitespace-nowrap">Tiago Fonseca (112346)</span>
      </footer>
    </>
  );
}
