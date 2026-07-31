# kafi-blog

Static site for the blog, plus every code sample it publishes.

The point of the layout: the Java lives in a real Maven module, and the site pulls
snippets out of those source files at build time. A snippet cannot drift from code that
compiles, because there is only one copy. CI compiles the Maven modules first and only
builds the site if that passes.

```
hugo.toml                 site config (Hugo root is the repo root, so shortcodes can read code/)
content/                  markdown
layouts/                  templates
  _shortcodes/snippet.html  extracts a marked region from a source file
  _shortcodes/fig.html      figure with caption
  _shortcodes/env.html      renders the disclosure block from data/environment.yaml
assets/css/main.css      stylesheet, includes generated Chroma token colours
static/diagrams/         committed SVGs
data/environment.yaml    the measurement environment, single source of truth
code/                    Maven parent, one module per article
bench/hardware.md        long-form notes about the measurement machine
.github/workflows/ci.yml compile samples, then build, then deploy
```

## Local

```sh
hugo server -D            # drafts included, live reload
hugo --gc --minify        # production build into public/
cd code && mvn -q verify  # compile and test the samples
hugo new content posts/my-article.md
```

## Snippets

Mark a region in the Java:

```java
// region:hot-loop
for (int i = 0; i < trips; i++) {
    acc += i;
}
// endregion:hot-loop
```

Reference it from markdown:

```
{{< snippet file="code/safepoints/src/main/java/dev/kafi/safepoints/PollDemo.java" region="hot-loop" >}}
```

The shortcode dedents the region and links the caption to the file on GitHub, building the
URL from `params.repo`. That is deliberately a different setting from `params.github`: the
masthead wants a profile URL, a blob link needs the repository path, and one value cannot
be both. A missing file or a missing region is a build error with the file and line of the
calling markdown, so a broken snippet cannot reach production.

Regions must have unique names within a file, and a region name must not be a prefix of
another region name in the same file.

Drafts are excluded from the production build, so a shortcode on a draft page never runs
there and a typo in one would surface only on the day the post is published. CI therefore
runs `hugo -D` into a throwaway directory first, purely to execute the shortcodes. That
step fails the job before anything is deployed.

## Disclosure

Edit `data/environment.yaml` once. Every article that calls `{{< env >}}` renders the same
block. All fields currently read `CHANGEME`.

## Deploying to GitHub Pages

1. Push to `main` on a public repo.
2. Settings, Pages, Source: GitHub Actions.
3. Set `baseURL` in `hugo.toml` to the real domain. Done, `https://kafi.dev/`.
4. Add `static/CNAME` containing the bare domain. Done, `kafi.dev`.
5. Settings, Pages, Custom domain: enter the domain, then tick Enforce HTTPS once the
   certificate is issued.
6. DNS at the registrar:
   - apex: four A records to `185.199.108.153`, `185.199.109.153`, `185.199.110.153`,
     `185.199.111.153`, and the equivalent AAAA records if you want IPv6.
   - `www`: CNAME to `<user>.github.io`.

   Verify these against GitHub's current documentation before relying on them, the
   apex addresses have changed before.

## Things to change before this is yours

- `hugo.toml`: done. `baseURL` is `https://kafi.dev/`, `params.github` is the profile,
  `params.repo` is this repository, `params.email` is set. `params.repo` is a guess at
  the repository name; correct it if the repo is not called `kafi-blog`, or every snippet
  caption will link to a 404.
- `data/environment.yaml`: all of it. Still `CHANGEME`, and it renders into every article
  that calls `{{< env >}}`.
- `content/about.md`: written from your own notes, edit the register to taste.
- `.github/workflows/ci.yml`: `HUGO_VERSION` is pinned to 0.164.0. Pin deliberately,
  and bump it when you choose to, not automatically.

## Known compromises

**Fonts load from Google Fonts.** Two extra DNS lookups and a render-blocking request on
a blog about latency. Self-host Newsreader and IBM Plex Mono as woff2 in
`static/fonts/`, declare `@font-face` with `font-display: swap`, and drop the
`fonts.googleapis.com` links from `layouts/_partials/head.html`. Worth doing before the
first article ships.

**`enableGitInfo` is off.** Turn it on in `hugo.toml` after the repo exists to get
last-modified dates from git history. It fails the build if there is no `.git`.

**Chroma CSS is generated and committed** into the tail of `main.css`. Regenerate with
`hugo gen chromastyles --style=github` if you change themes. It is marked in the file.

## What was actually tested

Built and verified with Hugo 0.164.0 extended on Linux, JDK 21.0.11:

- Clean production build, no warnings, no errors.
- Both `snippet` calls extract the correct region and dedent correctly.
- A missing region fails the build with a useful message.
- `PollDemo.java` compiles with `javac --release 21` and runs.

Since verified with Hugo 0.164.0 extended and Maven 3.9.16 on macOS arm64, Zulu 21.48.17:

- `mvn -B verify` against both `pom.xml` files. Passes, no warnings under `-Xlint:all`.
- Both builds clean: `hugo --gc --minify` (9 pages) and `hugo -D` (16 pages).
- All three shortcodes checked by reading the generated HTML, not just by exit code.
- A broken snippet, both a missing region and a missing file, fails the build with the
  file and line of the calling markdown.

Still not tested:

- The GitHub Actions workflow. Expect one round of fixing on first push.

## Note on the sample harness

The spinner now outlives the probe loop. It previously made one
`count(Integer.MAX_VALUE)` call, which finishes in well under a second, so only the
first requested collection measured anything and every later round collapsed to
microseconds. `spin()` re-enters `count()` until the prober sets `running = false`.

An int-counted loop cannot simply be made longer: the trip count saturates at
`Integer.MAX_VALUE`, which on a current CPU is a fraction of a second. Re-entering is
the workaround, and it has a consequence worth stating in the article. Each call
boundary is itself a poll point, so a request that lands on one sees a short wait. Read
the distribution, never a single round.

Measured on the machine this was last built on, `-XX:+UseSerialGC`, Zulu 21.48.17,
Apple Silicon, 12 rounds: round 0 blocked 177 ms, every later round blocked between
1.01 and 1.79 s. With `-Xlog:safepoint` the same run showed `Reaching safepoint` of
about 1.1 s against an `At safepoint` of 1.4 ms, which is the whole point of the piece:
a tool that reports only collection time would call that a 1.4 ms pause.

Still not evidence of anything: one collector, one machine, no percentiles, no repeat
runs. That work is unchanged and still ahead of the article.

The diagram has been redrawn to the five phases JDK 21 actually logs: `Time since
last`, `Reaching safepoint`, `Cleanup`, `At safepoint`, `Leaving safepoint`. It also
annotates that `Total` is the sum of the last four and excludes `Time since last`,
which was verified against the log on three consecutive collections rather than assumed.
