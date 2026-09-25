import { STARTER_QUESTIONS } from './starters'

export function EmptyState({ onPick }: { onPick: (question: string) => void }) {
  return (
    <section
      aria-labelledby="hero-title"
      className="relative mx-auto flex w-full max-w-[760px] flex-col px-4 pt-[12vh] pb-10 sm:px-6"
    >
      {/* soft juniper glow behind the wordmark */}
      <div
        aria-hidden
        className="pointer-events-none absolute top-[4vh] -left-24 -z-10 h-80 w-[36rem] max-w-[100vw]"
        style={{ background: 'radial-gradient(closest-side, var(--glow), transparent)' }}
      />
      <h1
        id="hero-title"
        className="font-mono text-[clamp(1.75rem,5.5vw,2.75rem)] leading-none font-medium tracking-[-0.04em]"
      >
        <span className="text-muted-foreground select-none" aria-hidden>
          ${' '}
        </span>
        claude-code-coach
        <span
          aria-hidden
          className="ml-2 inline-block h-[0.85em] w-[0.5em] translate-y-[0.08em] rounded-[2px] bg-primary motion-safe:animate-[caret-blink_1.1s_steps(1)_infinite]"
        />
      </h1>
      <p className="mt-5 max-w-[34rem] text-[1.0625rem] leading-relaxed text-pretty text-muted-foreground">
        Ask anything about Claude Code. Answers come from the official docs, with commands and config you can
        copy straight into your Java project.
      </p>

      <h2 className="sr-only">Starter questions</h2>
      <ul className="mt-10 grid gap-2.5 sm:grid-cols-2">
        {STARTER_QUESTIONS.map(({ icon: Icon, text }) => (
          <li key={text}>
            <button
              type="button"
              onClick={() => onPick(text)}
              className="group flex h-full w-full items-start gap-3 rounded-xl border bg-card px-4 py-3.5 text-left text-[0.9375rem] leading-snug shadow-[var(--shadow-soft)] transition-[background-color,border-color,transform] duration-150 hover:border-primary/40 hover:bg-accent/60 active:scale-[0.99]"
            >
              <Icon
                aria-hidden
                className="mt-0.5 size-4 shrink-0 text-muted-foreground transition-colors group-hover:text-primary"
              />
              <span>{text}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}
