// A clapperboard with its clapper raised, drawn in the text colour so it follows light and dark mode.
export default function MoviesLogo({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 64 64"
      fill="none"
      stroke="currentColor"
      strokeWidth={2.5}
      strokeLinecap="round"
      strokeLinejoin="round"
      role="img"
      aria-label="Movies"
      className={className}
    >
      <g transform="rotate(-12 8 24)">
        <rect x="8" y="14" width="48" height="10" rx="1.5" />
        <path d="M18 14l-6 10M30 14l-6 10M42 14l-6 10M54 14l-6 10" />
      </g>
      <rect x="8" y="26" width="48" height="30" rx="3" />
      <path d="M8 34h48M18 26l-6 8M30 26l-6 8M42 26l-6 8M54 26l-6 8" />
      <path d="M28 40v10l9-5z" fill="currentColor" />
    </svg>
  );
}
