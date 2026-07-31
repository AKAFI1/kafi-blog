#!/usr/bin/env python3
"""Regenerate the chroma section of main.css.

Two things the plain `hugo gen chromastyles` output cannot do on its own:

1. github-dark omits Punctuation, NameAttribute, NameBuiltin and BuiltinPseudo because
   it expects them to inherit the base foreground. The light theme *does* define them,
   so in dark mode they kept the light value (#1f2328) and became near-black text on a
   near-black background. We emit them explicitly using github-dark's own base colour.

2. The site has a manual theme toggle that sets data-theme on <html>. A dark block
   gated only on prefers-color-scheme ignores it, so code would keep the system palette
   while the rest of the page followed the reader's choice. Each dark rule is therefore
   emitted twice: once inside the media query, guarded so an explicit light choice wins,
   and once for an explicit dark choice.
"""
import re
import subprocess
import sys

RULE = re.compile(r'^(?P<lead>/\*.*?\*/\s*)?(?P<sels>[^{]+?)\s*\{(?P<body>[^}]*)\}\s*$')


def gen(style):
    out = subprocess.run(['hugo', 'gen', 'chromastyles', '--style', style],
                         capture_output=True, text=True, check=True).stdout
    return [l.rstrip() for l in out.splitlines()]


def parse(lines):
    """-> list of (lead_comment, [selectors], body) plus the set of token classes seen."""
    rules, tokens = [], set()
    for line in lines:
        if not line.strip() or line.lstrip().startswith('/* Generated using'):
            continue
        m = RULE.match(line.strip())
        if not m:
            continue
        sels = [s.strip() for s in m.group('sels').split(',')]
        rules.append((m.group('lead') or '', sels, m.group('body').strip()))
        for s in sels:
            t = re.fullmatch(r'\.chroma \.([a-z0-9]+)', s)
            if t:
                tokens.add(t.group(1))
    return rules, tokens


def base_fg(lines):
    for line in lines:
        if '/* Background */' in line:
            m = re.search(r'color:(#[0-9a-fA-F]{6})', line)
            if m:
                return m.group(1)
    sys.exit('could not find the dark base foreground')


def emit(rules, prefix=''):
    out = []
    for lead, sels, body in rules:
        # .bg is a background-only rule; the override below makes it transparent anyway.
        sels = [s for s in sels if s != '.bg']
        if not sels:
            continue
        joined = ', '.join((prefix + ' ' + s) if prefix else s for s in sels)
        out.append(f'{lead}{joined} {{ {body} }}')
    return out


light_lines, dark_lines = gen('github'), gen('github-dark')
light_rules, light_tokens = parse(light_lines)
dark_rules, dark_tokens = parse(dark_lines)

missing = sorted(light_tokens - dark_tokens)
fg = base_fg(dark_lines)
for tok in missing:
    dark_rules.append((f'/* restored: github-dark omits this, github defines it */ ',
                       [f'.chroma .{tok}'], f'color:{fg}'))

body = []
body.append('/* ---------- chroma: generated, do not hand-edit ---------- */')
body.append('/* regenerate: python3 tools/gen_chroma.py (see that file for why) */')
body.append('')
body += emit(light_rules)
body.append('')
body.append('/* Dark, route 1: the system preference, unless an explicit light choice overrides it. */')
body.append('@media (prefers-color-scheme: dark) {')
body += ['  ' + l for l in emit(dark_rules, ':root:not([data-theme="light"])')]
body.append('}')
body.append('')
body.append('/* Dark, route 2: an explicit choice from the theme toggle. */')
body += emit(dark_rules, ':root[data-theme="dark"]')
body.append('')
body.append('/* ---------- chroma overrides: keep the paper palette, take only the tokens ---------- */')
body.append('.chroma, .bg { background-color: transparent !important; }')

css = open('assets/css/main.css').read().splitlines()
cut = next(i for i, l in enumerate(css) if 'chroma: generated' in l)
open('assets/css/main.css', 'w').write('\n'.join(css[:cut] + body) + '\n')

print(f'light rules: {len(light_rules)}  dark rules: {len(dark_rules)}')
print(f'dark base fg: {fg}')
print(f'tokens restored for dark: {missing}')
