"""Asserts the live sites still match what MyCurated.kt parses.

Run before rebuilding the APK: `python check.py`. Failure here means a site changed and the
Kotlin parser needs updating -- not that the build is broken. Mirrors the Kotlin logic:
probe the post type, page through the chapter list, parse chapter numbers from titles,
then pull one chapter's images by slug.
"""

import html
import json
import re
import sys
import urllib.error
import urllib.request

MANIFEST = "manga.json"
PER_PAGE = 100
MAX_PAGES = 50
CHAPTER_NUMBER = re.compile(r"chapter\s*([\d.]+)", re.I)
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120 Safari/537.36"
)


def get(url):
    """Returns (status, body). Follows redirects, which the bare mirror domains need."""
    try:
        with urllib.request.urlopen(
            urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60
        ) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, ""


def rest(site, post_type, query):
    return f"{site}/wp-json/wp/v2/{post_type}?{query}"


def post_type(site):
    status, body = get(rest(site, "comic", "per_page=1"))
    return "comic" if status == 200 and body.strip() != "[]" else "posts"


def chapter_list(site, kind):
    posts, page = [], 1
    while page <= MAX_PAGES:
        status, body = get(
            rest(site, kind, f"per_page={PER_PAGE}&page={page}&_fields=slug,title,date_gmt")
        )
        if status != 200:
            break
        batch = json.loads(body)
        posts += batch
        if len(batch) < PER_PAGE:
            break
        page += 1
    return posts


def check(entry):
    site = entry["url"].rstrip("/")
    kind = post_type(site)
    posts = chapter_list(site, kind)
    assert posts, f"no chapters returned from {site} ({kind})"

    numbered, unnumbered = [], []
    for post in posts:
        title = html.unescape(post["title"]["rendered"])
        match = CHAPTER_NUMBER.search(title)
        (numbered if match else unnumbered).append(title)
    assert not unnumbered, f"{len(unnumbered)} titles have no chapter number, e.g. {unnumbered[:2]}"

    # Pages come from a slug lookup, exactly as getPageList does.
    slug = posts[0]["slug"]
    status, body = get(rest(site, kind, f"slug={slug}&_fields=content"))
    assert status == 200, f"slug lookup failed with HTTP {status}"
    found = json.loads(body)
    assert found, f"slug '{slug}' returned nothing"
    images = re.findall(r"<img[^>]+\ssrc=\"([^\"]+)\"", found[0]["content"]["rendered"])
    assert images, f"no <img src> in chapter '{slug}'"

    print(f"  type={kind} chapters={len(posts)} newest='{slug}' images={len(images)}")


def main():
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
