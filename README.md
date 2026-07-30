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

The shortcode dedents the region and links the caption to the file on GitHub. A missing
file or a missing region is a build error with the file and line of the calling markdown,
so a broken snippet cannot reach production.

Regions must have unique names within a file, and a region name must not be a prefix of
another region name in the same file.

## Disclosure

Edit `data/environment.yaml` once. Every article that calls `{{< env >}}` renders the same
block. All fields currently read `CHANGEME`.

## Deploying to GitHub Pages

1. Push to `main` on a public repo.
2. Settings, Pages, Source: GitHub Actions.
3. Set `baseURL` in `hugo.toml` to the real domain.
4. Add `static/CNAME` containing the bare domain, e.g. `ahmedkafi.dev`.
5. Settings, Pages, Custom domain: enter the domain, then tick Enforce HTTPS once the
   certificate is issued.
6. DNS at the registrar:
   - apex: four A records to `185.199.108.153`, `185.199.109.153`, `185.199.110.153`,
     `185.199.111.153`, and the equivalent AAAA records if you want IPv6.
   - `www`: CNAME to `<user>.github.io`.

   Verify these against GitHub's current documentation before relying on them, the
   apex addresses have changed before.

## Things to change before this is yours

- `hugo.toml`: `baseURL`, `params.github`, `params.email`.
- `data/environment.yaml`: all of it.
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

Not tested, because Maven Central was unreachable in the environment this was scaffolded
in:

- `mvn verify` against the two `pom.xml` files. Run it locally before pushing.
- The GitHub Actions workflow. Expect one round of fixing on first push.

## Note on the sample harness

`PollDemo` runs, but it is not yet a valid experiment. On a single test run under
`-XX:+UseSerialGC` on JDK 21.0.11 the first requested collection showed a 21 ms
`Reaching safepoint` and later ones showed microseconds, because `count(Integer.MAX_VALUE)`
completes in about a second and the measurement window closes with it. Before this becomes
an article the spinner needs to run for the whole measurement period, and one run on one
collector is not evidence of anything.

Also worth correcting: JDK 21 logs five phases, not three. `Time since last`,
`Reaching safepoint`, `Cleanup`, `At safepoint`, `Leaving safepoint`. The committed
diagram simplifies to three and should be redrawn or captioned to say so.
