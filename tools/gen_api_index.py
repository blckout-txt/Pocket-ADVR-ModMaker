#!/usr/bin/env python3
"""
Parses the ADVR Modding Tools LuaLS definition stubs into a compact, line-oriented
index that the Android app loads at startup.

Record format (pipe separated, one record per line):

  # <comment, ignored by the loader>
  V|<api version>
  C|<class>|<parent>|<doc>              class declaration
  F|<class>|<field>|<type>|<doc>        field on a class
  M|<class>|<method>|<params>|<ret>|<doc>   method overload (repeats per overload)
  G|<global>|<class>                    global variable -> class it is typed as

  <params> is a comma separated list of `name:type` pairs (may be empty).

Written as UTF-8 to app/src/main/assets/advr_api.txt. It is *not* gzipped: the Android asset
merger silently gunzips `.gz` assets and drops the extension, and the APK deflates the file anyway
(3.8 MiB of text lands at ~350 KiB inside the APK).
"""

import glob
import json
import os
import re
import sys
from collections import OrderedDict

# Where the ADVR Modding Tools extension unpacks itself, across the editors people use.
EXTENSION_GLOBS = [
    '~/.vscode/extensions/erthugames.advr-modding-tools-*',
    '~/.vscode-oss/extensions/erthugames.advr-modding-tools-*',
    '~/.vscode-server/extensions/erthugames.advr-modding-tools-*',
    '~/.cursor/extensions/erthugames.advr-modding-tools-*',
    '~/.windsurf/extensions/erthugames.advr-modding-tools-*',
]

CLASS_RE = re.compile(r'^---\s*@class\s+([A-Za-z_][\w.]*)\s*(?::\s*(.+?))?\s*$')
FIELD_RE = re.compile(r'^---\s*@field\s+(?:(?:public|private|protected|package)\s+)?([A-Za-z_][\w]*)\s+(.*)$')
PARAM_RE = re.compile(r'^---\s*@param\s+([A-Za-z_][\w]*|\.\.\.)\s*(.*)$')
RETURN_RE = re.compile(r'^---\s*@return\s+(.*)$')
TYPE_RE = re.compile(r'^---\s*@type\s+(.*)$')
DOC_RE = re.compile(r'^---\s?(.*)$')
ASSIGN_RE = re.compile(r'^([A-Za-z_][\w.]*)\s*=\s*\{\s*\}\s*$')
FUNC_RE = re.compile(r'^function\s+([A-Za-z_][\w.]*)[.:]([A-Za-z_][\w]*)\s*\((.*?)\)\s*end\s*$')


def split_type(rest):
    """Split `<type> <description>` where <type> may be a `fun(a: X, b: Y):Z` blob."""
    rest = rest.strip()
    if not rest:
        return '', ''
    if rest.startswith('fun('):
        depth = 0
        i = 3  # index of '('
        while i < len(rest):
            ch = rest[i]
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
                if depth == 0:
                    i += 1
                    break
            i += 1
        # optional `:ReturnType` directly after the closing paren
        if i < len(rest) and rest[i] == ':':
            i += 1
            while i < len(rest) and (rest[i].isalnum() or rest[i] in '_|[].<>, '):
                # stop at a double space, which separates type from description
                if rest[i] == ' ' and (i + 1 >= len(rest) or rest[i + 1] == ' '):
                    break
                i += 1
        return rest[:i].strip(), rest[i:].strip()
    parts = rest.split(None, 1)
    return parts[0], (parts[1].strip() if len(parts) > 1 else '')


def clean(s):
    """Records are pipe delimited, so pipes inside values become the unicode bar."""
    return (s or '').replace('|', '│').replace('\n', ' ').strip()


class Klass:
    __slots__ = ('name', 'parent', 'doc', 'fields', 'methods')

    def __init__(self, name, parent, doc):
        self.name = name
        self.parent = parent or ''
        self.doc = doc or ''
        self.fields = OrderedDict()   # name -> (type, doc)
        self.methods = OrderedDict()  # name -> [ (params, ret, doc) ]


def parse_file(path, classes, globals_map):
    with open(path, 'r', encoding='utf-8', errors='replace') as fh:
        lines = fh.read().splitlines()

    cur = None          # class currently accepting @field lines
    doc_lines = []      # free text doc lines of the pending block
    params = []         # pending @param entries
    returns = []        # pending @return entries
    type_ann = None     # pending @type
    last_class = None   # last @class seen in this file, for `X = {}`

    def reset_block():
        nonlocal doc_lines, params, returns, type_ann
        doc_lines, params, returns, type_ann = [], [], [], None

    for raw in lines:
        line = raw.rstrip()
        if not line.strip():
            continue

        if line.startswith('---'):
            m = CLASS_RE.match(line)
            if m:
                name, parent = m.group(1), m.group(2)
                doc = ' '.join(d for d in doc_lines if d)
                if name in classes:
                    cur = classes[name]
                    if parent and not cur.parent:
                        cur.parent = parent
                else:
                    cur = Klass(name, parent, doc)
                    classes[name] = cur
                last_class = cur
                reset_block()
                continue

            m = FIELD_RE.match(line)
            if m and cur is not None:
                fname, rest = m.group(1), m.group(2)
                ftype, fdoc = split_type(rest)
                # Overloaded fun-typed fields: keep the first, they only differ in arity.
                if fname not in cur.fields:
                    cur.fields[fname] = (ftype, fdoc)
                continue

            m = PARAM_RE.match(line)
            if m:
                pname, rest = m.group(1), m.group(2)
                ptype, pdoc = split_type(rest)
                params.append((pname, ptype or 'any'))
                continue

            m = RETURN_RE.match(line)
            if m:
                rtype, _ = split_type(m.group(1))
                returns.append(rtype or 'any')
                continue

            m = TYPE_RE.match(line)
            if m:
                type_ann, _ = split_type(m.group(1))
                continue

            if line.startswith('--- @meta') or line.startswith('---@meta'):
                continue

            m = DOC_RE.match(line)
            if m:
                doc_lines.append(m.group(1).strip())
            continue

        # ---- real code lines ----
        m = ASSIGN_RE.match(line)
        if m:
            gname = m.group(1)
            gtype = type_ann or (last_class.name if last_class else '')
            if gtype:
                globals_map[gname] = gtype
            reset_block()
            continue

        m = FUNC_RE.match(line)
        if m:
            owner, method, arglist = m.group(1), m.group(2), m.group(3)
            owner_class = globals_map.get(owner, owner)
            k = classes.get(owner_class)
            if k is None:
                k = Klass(owner_class, '', '')
                classes[owner_class] = k
            argnames = [a.strip() for a in arglist.split(',') if a.strip()]
            typed = []
            for i, an in enumerate(argnames):
                ptype = 'any'
                for pn, pt in params:
                    if pn == an:
                        ptype = pt
                        break
                else:
                    if i < len(params):
                        ptype = params[i][1]
                typed.append((an, ptype))
            ret = returns[0] if returns else 'any'
            doc = ' '.join(d for d in doc_lines if d)
            k.methods.setdefault(method, [])
            sig = (typed, ret, doc)
            if sig not in k.methods[method]:
                k.methods[method].append(sig)
            reset_block()
            continue

        reset_block()


def version_key(path):
    """Sorts extension folders by their trailing version so the newest install wins."""
    m = re.search(r'-(\d+(?:\.\d+)*)$', os.path.basename(path.rstrip('/')))
    return tuple(int(p) for p in m.group(1).split('.')) if m else (0,)


def find_extension():
    """Newest installed ADVR Modding Tools extension directory, or None."""
    found = []
    for pattern in EXTENSION_GLOBS:
        found.extend(glob.glob(os.path.expanduser(pattern)))
    found = [p for p in found if os.path.isdir(os.path.join(p, 'lua-definitions', 'library'))]
    if not found:
        return None
    return sorted(found, key=version_key)[-1]


def read_version(library_dir):
    """The extension's package.json sits two levels above lua-definitions/library."""
    ext_root = os.path.dirname(os.path.dirname(os.path.abspath(library_dir)))
    pkg = os.path.join(ext_root, 'package.json')
    if os.path.exists(pkg):
        try:
            with open(pkg, encoding='utf-8') as fh:
                return json.load(fh).get('version', '')
        except (OSError, ValueError):
            pass
    # Fall back to the version in the folder name, e.g. erthugames.advr-modding-tools-1.101.0
    m = re.search(r'-(\d+(?:\.\d+)*)$', os.path.basename(ext_root))
    return m.group(1) if m else 'unknown'


def main():
    if len(sys.argv) > 1:
        src = os.path.expanduser(sys.argv[1])
    else:
        ext = find_extension()
        if ext is None:
            sys.exit(
                'Could not find the ADVR Modding Tools extension.\n'
                'Install it in VS Code, or pass the stub directory explicitly:\n'
                '  python3 tools/gen_api_index.py <.../lua-definitions/library> [output]'
            )
        src = os.path.join(ext, 'lua-definitions', 'library')

    if not os.path.isdir(src):
        sys.exit('Not a directory: %s' % src)

    out = sys.argv[2] if len(sys.argv) > 2 else (
        os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                     'app/src/main/assets/advr_api.txt'))

    classes, globals_map = OrderedDict(), OrderedDict()
    files = []
    for root, _dirs, names in os.walk(src):
        for n in sorted(names):
            if n.endswith('.lua'):
                files.append(os.path.join(root, n))
    files.sort()
    for path in files:
        parse_file(path, classes, globals_map)

    version = read_version(src)

    # `#` lines are provenance for anyone opening the asset; the loader skips them.
    rows = [
        '# Generated by tools/gen_api_index.py - do not edit by hand.',
        '# Source: ADVR Modding Tools %s Lua definitions.' % version,
        '# Copyright (c) 2025 ErThu Games GmbH, MIT licensed. See THIRD_PARTY_NOTICES.md.',
        'V|' + version,
    ]
    for k in classes.values():
        rows.append('C|%s|%s|%s' % (clean(k.name), clean(k.parent), clean(k.doc)))
        for fname, (ftype, fdoc) in k.fields.items():
            rows.append('F|%s|%s|%s|%s' % (clean(k.name), clean(fname), clean(ftype), clean(fdoc)))
        for mname, sigs in k.methods.items():
            for typed, ret, doc in sigs:
                ps = ','.join('%s:%s' % (clean(pn), clean(pt)) for pn, pt in typed)
                rows.append('M|%s|%s|%s|%s|%s' % (clean(k.name), clean(mname), ps, clean(ret), clean(doc)))
    for gname, gtype in globals_map.items():
        rows.append('G|%s|%s' % (clean(gname), clean(gtype)))

    payload = '\n'.join(rows).encode('utf-8')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'wb') as fh:
        fh.write(payload)

    n_fields = sum(len(k.fields) for k in classes.values())
    n_over = sum(len(s) for k in classes.values() for s in k.methods.values())
    n_meth = sum(len(k.methods) for k in classes.values())
    print('source           %s' % src)
    print('api version      %s' % version)
    print('files            %d' % len(files))
    print('classes          %d' % len(classes))
    print('globals          %d' % len(globals_map))
    print('fields           %d' % n_fields)
    print('methods          %d (%d overloads)' % (n_meth, n_over))
    print('records          %d' % len(rows))
    print('written          %.1f KiB -> %s' % (len(payload) / 1024.0, out))
    print('  (the APK deflates this to roughly %.0f KiB)' % (len(payload) / 1024.0 / 10.8))


if __name__ == '__main__':
    main()
