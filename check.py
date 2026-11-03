"""Asserts the live sites still match what MyCurated.kt parses.

Run before rebuilding the APK: `python check.py`. Failure here means a site changed and the
Kotlin parser needs updating -- not that the build is broken. Mirrors the Kotlin logic,
including the dispatch: a manifest URL with a path is a Madara series, a bare host is a
WordPress REST site.
"""

import html
import json
import re
import sys
import urllib.error
import urllib.request
from collections import Counter
from urllib.parse import urlparse

MANIFEST = "manga.json"
PER_PAGE = 100
MAX_PAGES = 50
CHAPTER_NUMBER = re.compile(r"chapter\s*([\d.]+)", re.I)
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120 Safari/537.36"
)


def get(url, data=None):
    """Returns (status, body); POSTs when data is set. Follows redirects, which the bare
    mirror domains need."""
    try:
        with urllib.request.urlopen(
            urllib.request.Request(url, data=data, headers={"User-Agent": UA}), timeout=60
        ) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, ""


def number(title):
    match = CHAPTER_NUMBER.search(title)
    return float(match.group(1)) if match else None


def assert_chapters(names, site):
    """The two ways a chapter list turns out not to be a chapter list."""
    unnumbered = [n for n in names if number(n) is None]
    assert not unnumbered, f"{len(unnumbered)} titles have no chapter number, e.g. {unnumbered[:2]}"

    # A real series occasionally re-publishes a chapter under a new slug (fixed re-upload,
    # renamed title) -- that's a pair, at most, however many chapters it happens to. A blog
    # mixing several series collides on the same number three times or more, since every
    # series restarts around "chapter 1".
    counts = Counter(number(n) for n in names)
    overcrowded = {n: c for n, c in counts.items() if c > 2}
    assert not overcrowded, (
        f"chapter numbers appear 3+ times, e.g. {list(overcrowded.items())[:5]} -- {site} is "
        "probably several series, or a blog, rather than one manga"
    )


# --- WordPress REST: the manifest entry is a site root ------------------------------------

def rest_url(site, post_type, query):
    return f"{site}/wp-json/wp/v2/{post_type}?{query}"


CUSTOM_TYPES = ("comic", "manga")


def post_type(site):
    for type_ in CUSTOM_TYPES:
        status, body = get(rest_url(site, type_, "per_page=1"))
        if status == 200 and body.strip() != "[]":
            return type_
    return "posts"


def chapter_list(site, kind):
    posts, page = [], 1
    while page <= MAX_PAGES:
        status, body = get(
            rest_url(site, kind, f"per_page={PER_PAGE}&page={page}&_fields=slug,title,date_gmt")
        )
        if status != 200:
            break
        batch = json.loads(body)
        posts += batch
        if len(batch) < PER_PAGE:
            break
        page += 1
    return posts


def check_rest(site):
    kind = post_type(site)
    posts = chapter_list(site, kind)
    assert posts, f"no chapters returned from {site} ({kind})"
    assert_chapters([html.unescape(p["title"]["rendered"]) for p in posts], site)

    # Pages come from a slug lookup, exactly as restImages does.
    slug = posts[0]["slug"]
    status, body = get(rest_url(site, kind, f"slug={slug}&_fields=content"))
    assert status == 200, f"slug lookup failed with HTTP {status}"
    found = json.loads(body)
    assert found, f"slug '{slug}' returned nothing"
    # Sites differ on quote style, and src can be the first attribute (nothing before it to
    # make [^>]+ match) -- Jsoup in the Kotlin parser cares about neither.
    images = re.findall(r"<img[^>]*\ssrc=[\"']([^\"']+)[\"']", found[0]["content"]["rendered"])
    assert images, f"no <img src> in chapter '{slug}'"

    return f"  shape=rest type={kind} chapters={len(posts)} newest='{slug}' images={len(images)}"


# --- Madara: the manifest entry is a series URL -------------------------------------------

def check_madara(site):
    status, body = get(site + "/ajax/chapters/", data=b"")
    assert status == 200, f"ajax/chapters/ returned HTTP {status}"

    chapters = []
    # The theme wraps the attribute onto its own line, so <li> and class= are not adjacent.
    for item in re.findall(r"<li[^>]*class=\"wp-manga-chapter.*?</li>", body, re.S):
        link = re.search(r"<a href=\"([^\"]+)\"[^>]*>(.*?)</a>", item, re.S)
        assert link, f"chapter entry with no link: {item[:80]}"
        chapters.append((html.unescape(re.sub(r"\s+", " ", link.group(2)).strip()), link.group(1)))
    assert chapters, "no li.wp-manga-chapter entries in the ajax response"
    assert_chapters([name for name, _ in chapters], site)

    newest_name, newest_url = max(chapters, key=lambda c: number(c[0]))
    # Any query string bypasses the page cache; the cached copy ships an empty reader.
    status, page = get(newest_url + "?cache=0")
    assert status == 200, f"chapter page returned HTTP {status}"
    numbered = [t for t in re.findall(r"<img[^>]*>", page) if re.search(r"id=\"image-\d+\"", t)]
    images = [m.group(1) for m in (re.search(r"src=\"([^\"]+)\"", t) for t in numbered) if m]
    assert images, f"no id=image-N images in {newest_url}"

    return f"  shape=madara chapters={len(chapters)} newest='{newest_name}' images={len(images)}"


def check(entry):
    site = entry["url"].rstrip("/")
    # Same dispatch as chapterList: a path means one series on a multi-series host.
    print(check_madara(site) if urlparse(site).path.strip("/") else check_rest(site))


def main():
    # Windows consoles default to cp1252, which can't print the non-ASCII titles sites carry.
    sys.stdout.reconfigure(encoding="utf-8")
    with open(MANIFEST, encoding="utf-8") as fh:
        entries = json.load(fh)
    assert entries, "manifest is empty"

    failed = 0
    for entry in entries:
        assert "title" in entry and "url" in entry, f"bad entry: {entry}"
        print(f"{entry['title']} [{entry['url']}]")
        try:
            check(entry)
        except Exception as exc:
            print(f"  FAIL: {exc}")
            failed += 1

    print("FAILED" if failed else "OK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
