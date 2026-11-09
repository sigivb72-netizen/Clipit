"""Clip It download engine.

Runs inside the Python interpreter bundled with the app, one process per job.
Reads a JSON job description from the file given as argv[1] and reports back
with one JSON object per line on stdout, each prefixed with MARK. Every other
line on stdout/stderr is treated by the app as plain log output.

Job fields:
  mode        "download" | "versions"
  url         link to download
  out_dir     directory to place finished files in (created if missing)
  quality     max video resolution (e.g. 720); 0 = best available
  max_items   cap on files fetched from one link (profiles, playlists)
  cookies     path to a Netscape cookies.txt, or null
  credentials {gallery-dl category: {"username":..., "password":...}}
  ffmpeg      path to the ffmpeg binary, or null
  qjs         path to a QuickJS binary (YouTube needs a JS runtime), or null
  cache_dir   directory for yt-dlp / gallery-dl caches
  engine      "auto" | "gallery-dl" | "yt-dlp"
"""

import json
import logging
import os
import re
import shutil
import signal
import sys
import time

MARK = "@@CLIPIT@@"
_out = sys.stdout


def emit(t, **fields):
    fields["t"] = t
    _out.write(MARK + json.dumps(fields, ensure_ascii=True, default=str) + "\n")
    _out.flush()


AUTH_RE = re.compile(
    r"log[ -]?in|logged in|sign in|cookies|authenticat|authoriz|credential|"
    r"private|age[- ]restrict|members[- ]only|subscriber|not available to guest|"
    r"\b401\b|\b403\b|nsfw|confirm your age|protected",
    re.I)
UNSUPPORTED_RE = re.compile(r"unsupported url|no suitable extractor|no extractor", re.I)
NETWORK_RE = re.compile(
    r"timed out|timeout|connection|network|name resolution|getaddrinfo|"
    r"temporary failure|ssl|unreachable|reset by peer", re.I)


def classify(message, status=0):
    if status & 16 or AUTH_RE.search(message or ""):
        return "auth"
    if UNSUPPORTED_RE.search(message or ""):
        return "unsupported"
    if NETWORK_RE.search(message or ""):
        return "network"
    return "other"


class Progress:
    """Rate-limited progress reporting shared by both back ends."""

    def __init__(self):
        self.last = 0.0
        self.index = 0
        self.count = 0

    def report(self, downloaded, total, speed, eta=None, force=False):
        now = time.monotonic()
        if not force and now - self.last < 0.4:
            return
        self.last = now
        emit("progress", downloaded=int(downloaded or 0),
             total=int(total or 0), speed=int(speed or 0),
             eta=int(eta) if eta else 0, index=self.index, count=self.count)


PROGRESS = Progress()


def clean(value, limit=4000):
    """Reduce a metadata value to something JSON-friendly and small."""
    if value is None or isinstance(value, (bool, int, float)):
        return value
    if hasattr(value, "isoformat"):
        return value.isoformat(sep=" ") if hasattr(value, "hour") else value.isoformat()
    if isinstance(value, str):
        return value if len(value) <= limit else value[:limit] + "…"
    return None


def scalar_fields(source, skip=(), limit=60):
    """Every simple top-level field, so Details can show whatever exists."""
    extra = {}
    for key, value in source.items():
        if len(extra) >= limit:
            break
        if not isinstance(key, str) or key.startswith("_") or key in skip:
            continue
        value = clean(value, 600)
        if value is None or value == "":
            continue
        extra[key] = value
    return extra


def quality_opts(job):
    """yt-dlp format selection for the requested resolution cap.

    'res' compares the smaller dimension, so a 720x1280 portrait clip counts
    as 720p. yt-dlp prefers the best format at or below the cap and only goes
    above it when nothing smaller exists.
    """
    quality = int(job.get("quality") or 0)
    sort = ["vcodec:h264", "acodec:aac"]
    if quality > 0:
        sort.insert(0, "res:%d" % quality)
    return "bv*+ba/b", sort


def ytdl_params(job):
    fmt, sort = quality_opts(job)
    params = {
        "format": fmt,
        "format_sort": sort,
        "merge_output_format": "mp4",
        "noplaylist": True,
        "windowsfilenames": True,
        "trim_file_name": 150,
        "retries": 5,
        "fragment_retries": 5,
        "concurrent_fragment_downloads": 4,
        "socket_timeout": 25,
        "noprogress": True,
        "quiet": True,
        "color": "never",
    }
    if job.get("ffmpeg"):
        params["ffmpeg_location"] = job["ffmpeg"]
    if job.get("qjs"):
        params["js_runtimes"] = {"quickjs": {"path": job["qjs"]}}
    if job.get("cache_dir"):
        params["cachedir"] = os.path.join(job["cache_dir"], "yt-dlp")
    return params


# ───────────────────────────── yt-dlp ─────────────────────────────

YTDL_META = (
    "title", "uploader", "uploader_id", "uploader_url", "channel",
    "channel_url", "channel_follower_count", "description", "upload_date",
    "timestamp", "duration", "view_count", "like_count", "comment_count",
    "repost_count", "webpage_url", "extractor_key", "id", "width", "height",
    "fps", "resolution", "format_note", "vcodec", "acodec", "filesize",
    "filesize_approx", "thumbnail", "age_limit", "live_status", "playlist_title",
    "artist", "album", "track", "categories", "tags",
)


def ytdl_meta(info):
    meta = {}
    for key in YTDL_META:
        value = info.get(key)
        if isinstance(value, (list, tuple)):
            value = ", ".join(str(v) for v in value[:15] if isinstance(v, (str, int)))
        value = clean(value)
        if value not in (None, ""):
            meta[key] = value
    return {
        "title": meta.get("title"),
        "uploader": meta.get("uploader") or meta.get("channel") or meta.get("uploader_id"),
        "uploader_url": meta.get("uploader_url") or meta.get("channel_url"),
        "site": meta.get("extractor_key"),
        "post_url": meta.get("webpage_url"),
        "extra": meta,
    }


class YtdlLogger:
    def __init__(self):
        self.errors = []

    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        sys.stderr.write("yt-dlp: %s\n" % msg)

    def error(self, msg):
        self.errors.append(str(msg))
        sys.stderr.write("yt-dlp: %s\n" % msg)


def run_ytdlp(job):
    """Returns (files, error_message)."""
    import yt_dlp

    emit("engine", name="yt-dlp", version=yt_dlp.version.__version__)
    files = []
    logger = YtdlLogger()

    def hook(d):
        status = d.get("status")
        if status == "downloading":
            info = d.get("info_dict") or {}
            PROGRESS.index = info.get("playlist_index") or PROGRESS.index or 1
            PROGRESS.count = info.get("n_entries") or PROGRESS.count or 1
            PROGRESS.report(d.get("downloaded_bytes"),
                            d.get("total_bytes") or d.get("total_bytes_estimate"),
                            d.get("speed"), d.get("eta"))
        elif status == "finished":
            PROGRESS.report(d.get("downloaded_bytes"), d.get("total_bytes"),
                            0, force=True)

    def pp_hook(d):
        if d.get("status") == "started" and d.get("postprocessor") in (
                "Merger", "FFmpegMerger", "VideoRemuxer", "FFmpegVideoRemuxer"):
            emit("stage", name="merging")

    params = ytdl_params(job)
    params.update({
        "outtmpl": os.path.join(job["out_dir"], "%(title).120B [%(id)s].%(ext)s"),
        "logger": logger,
        "progress_hooks": [hook],
        "postprocessor_hooks": [pp_hook],
        "playlistend": int(job.get("max_items") or 10),
    })
    if job.get("cookies"):
        params["cookiefile"] = job["cookies"]
    for creds in (job.get("credentials") or {}).values():
        params.setdefault("username", creds.get("username"))
        params.setdefault("password", creds.get("password"))
        break

    sent_meta = False
    try:
        with yt_dlp.YoutubeDL(params) as ydl:
            info = ydl.extract_info(job["url"], download=False, process=False)
            # Only a link that is itself a playlist/profile carries entries here.
            if info and info.get("_type", "video") == "video":
                emit("meta", **ytdl_meta(info))
                sent_meta = True
            info = ydl.process_ie_result(info, download=True)
            entries = [info]
            if info and info.get("_type") == "playlist":
                entries = [e for e in (info.get("entries") or []) if e]
            for entry in entries:
                if not sent_meta:
                    emit("meta", **ytdl_meta(entry))
                    sent_meta = True
                for item in entry.get("requested_downloads") or ():
                    path = item.get("filepath")
                    if path and os.path.exists(path) and path not in files:
                        files.append(path)
    except Exception as exc:  # yt-dlp raises DownloadError for nearly everything
        message = re.sub(r"^ERROR:\s*", "", str(exc)) or exc.__class__.__name__
        return files, message
    if not files:
        return files, (logger.errors[-1] if logger.errors else "Nothing was downloaded")
    return files, None


# ──────────────────────────── gallery-dl ────────────────────────────

def first_text(source, keys):
    for key in keys:
        value = source.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return str(value)
    return None


def gdl_person(kwdict):
    """Best-effort uploader name and handle across gallery-dl extractors."""
    for key in ("author", "user", "uploader", "owner", "artist", "creator",
                "account", "channel", "blog", "username", "blog_name",
                "subreddit"):
        value = kwdict.get(key)
        if isinstance(value, dict):
            handle = first_text(value, ("name", "username", "screen_name",
                                        "account", "handle", "uniqueId", "id"))
            nick = first_text(value, ("nick", "display_name", "full_name",
                                      "nickname", "fullname", "title"))
            if handle or nick:
                return nick or handle, handle
        elif isinstance(value, str) and value.strip():
            return value.strip(), value.strip()
    return None, None


def gdl_meta(kwdict, url):
    name, handle = gdl_person(kwdict)
    if name and handle and name != handle:
        uploader = "%s (@%s)" % (name, handle)
    else:
        uploader = name
    title = first_text(kwdict, ("title", "content", "description", "caption",
                                "text", "filename"))
    if title:
        title = " ".join(title.split())[:200]
    extra = scalar_fields(kwdict, skip=("extension", "filename", "num", "count"))
    for key in ("author", "user"):
        value = kwdict.get(key)
        if isinstance(value, dict):
            for sub, subvalue in scalar_fields(value, limit=12).items():
                extra["%s.%s" % (key, sub)] = subvalue
    return {
        "title": title,
        "uploader": uploader,
        "uploader_url": first_text(kwdict, ("author_url", "user_url", "channel_url")),
        "site": first_text(kwdict, ("category",)),
        "post_url": first_text(kwdict, ("post_url", "permalink", "webpage_url",
                                        "tweet_url", "page_url")) or url,
        "extra": extra,
    }


class GdlOutput:
    """Replaces gallery-dl's terminal output with events for the app."""

    def __init__(self):
        self.files = []

    def start(self, path):
        PROGRESS.index += 1
        PROGRESS.count = max(PROGRESS.count, PROGRESS.index)

    def skip(self, path):
        pass

    def success(self, path):
        if path and path not in self.files and os.path.exists(path):
            self.files.append(path)
            emit("file", path=path)

    def progress(self, bytes_total, bytes_downloaded, bytes_per_second):
        PROGRESS.report(bytes_downloaded, bytes_total, bytes_per_second)


class GdlLogHandler(logging.Handler):
    def __init__(self):
        logging.Handler.__init__(self, logging.WARNING)
        self.errors = []

    def emit(self, record):
        try:
            message = record.getMessage()
        except Exception:
            return
        sys.stderr.write("gallery-dl: %s\n" % message)
        if record.levelno >= logging.ERROR:
            self.errors.append(message)


def patch_twitter_quality(quality):
    """gallery-dl always takes the highest-bitrate Twitter variant.

    Variant URLs carry their size (…/vid/avc1/720x1280/…), so trimming the
    list before gallery-dl picks from it applies the resolution cap without
    an extra round trip through yt-dlp. Any surprise leaves the list as is.
    """
    try:
        from gallery_dl.extractor import twitter
        original = twitter.TwitterExtractor._extract_media
    except Exception:
        return
    size = re.compile(r"/(\d{2,5})x(\d{2,5})/")

    def resolution(variant):
        match = size.search(variant.get("url") or "")
        return min(int(match.group(1)), int(match.group(2))) if match else None

    def patched(self, tweet, entities, files):
        try:
            for media in entities:
                info = media.get("video_info") if isinstance(media, dict) else None
                variants = info.get("variants") if info else None
                if not variants:
                    continue
                sized = [(resolution(v), v) for v in variants]
                sized = [(r, v) for r, v in sized if r]
                within = [v for r, v in sized if r <= quality]
                if within:
                    info["variants"] = within
                elif sized:
                    info["variants"] = [min(sized, key=lambda rv: rv[0])[1]]
        except Exception:
            pass
        return original(self, tweet, entities, files)

    twitter.TwitterExtractor._extract_media = patched


def gdl_configure(job):
    from gallery_dl import config

    config.clear()
    ext = ("extractor",)
    config.set(ext, "base-directory", job["out_dir"])
    config.set(ext, "directory", [])
    config.set(ext, "skip", False)
    config.set(ext, "retries", 4)
    config.set(ext, "timeout", 25.0)
    config.set(ext, "file-range", "1-%d" % int(job.get("max_items") or 10))
    config.set(ext, "cookies-update", False)
    config.set(ext, "path-restrict", "windows")
    config.set(ext, "path-extended", False)
    if job.get("cookies"):
        config.set(ext, "cookies", job["cookies"])
    for category, creds in (job.get("credentials") or {}).items():
        config.set(("extractor", category), "username", creds.get("username"))
        config.set(("extractor", category), "password", creds.get("password"))
    # Links gallery-dl cannot handle natively are retried with yt-dlp by run()
    # so that the richer yt-dlp metadata reaches the app.
    config.set(("extractor", "ytdl"), "enabled", False)

    fmt, _ = quality_opts(job)
    raw = ytdl_params(job)
    raw.pop("format", None)
    for section in (("extractor", "ytdl"), ("downloader", "ytdl")):
        config.set(section, "module", "yt_dlp")
        config.set(section, "format", fmt)
        config.set(section, "raw-options", raw)
    config.set(("downloader",), "progress", 0.4)
    config.set(("downloader",), "part-directory", None)
    config.set(("output",), "mode", "null")
    if job.get("cache_dir"):
        config.set(("cache",), "file",
                   os.path.join(job["cache_dir"], "gallery-dl.sqlite3"))


def run_gallery(job):
    """Returns (files, error_message, status) or None if no extractor matches."""
    import gallery_dl
    from gallery_dl import extractor, job as gjob, output

    gdl_configure(job)
    if extractor.find(job["url"]) is None:
        return None

    emit("engine", name="gallery-dl", version=gallery_dl.version.__version__)
    quality = int(job.get("quality") or 0)
    if quality > 0:
        patch_twitter_quality(quality)

    output.initialize_logging(logging.WARNING)
    handler = GdlLogHandler()
    root = logging.getLogger()
    root.handlers[:] = [handler]

    out = GdlOutput()
    state = {"meta": False}

    class ClipJob(gjob.DownloadJob):
        def __init__(self, url, parent=None):
            gjob.DownloadJob.__init__(self, url, parent)
            self.out = out

        def handle_url(self, url, kwdict):
            if not state["meta"]:
                state["meta"] = True
                try:
                    emit("meta", **gdl_meta(kwdict, job["url"]))
                except Exception as exc:
                    sys.stderr.write("metadata: %r\n" % (exc,))
            gjob.DownloadJob.handle_url(self, url, kwdict)

    try:
        status = ClipJob(job["url"]).run()
    except Exception as exc:
        return out.files, "%s: %s" % (exc.__class__.__name__, exc), 1
    if out.files:
        return out.files, None, status
    message = handler.errors[-1] if handler.errors else "Nothing was downloaded"
    return out.files, message, status


# ───────────────────────────── driver ─────────────────────────────

def versions():
    found = {}
    try:
        import yt_dlp
        found["yt-dlp"] = yt_dlp.version.__version__
    except Exception as exc:
        found["yt-dlp-error"] = repr(exc)
    try:
        import gallery_dl
        found["gallery-dl"] = gallery_dl.version.__version__
    except Exception as exc:
        found["gallery-dl-error"] = repr(exc)
    found["python"] = sys.version.split()[0]
    emit("versions", **found)


def private_cookies(job):
    """Both tools may rewrite the cookie jar, so every job gets its own copy."""
    source = job.get("cookies")
    if not source or not os.path.isfile(source) or os.path.getsize(source) == 0:
        job["cookies"] = None
        return
    target = os.path.join(job["cache_dir"] or job["out_dir"],
                          "cookies-%d.txt" % os.getpid())
    shutil.copyfile(source, target)
    job["cookies"] = target


def download(job):
    os.makedirs(job["out_dir"], exist_ok=True)
    if job.get("cache_dir"):
        os.makedirs(job["cache_dir"], exist_ok=True)
    private_cookies(job)
    engine = job.get("engine") or "auto"
    try:
        errors = []
        if engine in ("auto", "gallery-dl"):
            result = run_gallery(job)
            if result is not None:
                files, error, status = result
                if files:
                    return emit("done", files=files, engine="gallery-dl",
                                warning=error)
                code = classify(error, status)
                errors.append((code, "gallery-dl: " + error))
                if code == "auth" or engine == "gallery-dl":
                    return emit("error", code=code, message=errors[0][1])
            elif engine == "gallery-dl":
                return emit("error", code="unsupported",
                            message="gallery-dl does not support this link")
        files, error = run_ytdlp(job)
        if files:
            return emit("done", files=files, engine="yt-dlp", warning=error)
        code = classify(error)
        # When both failed, gallery-dl's reason is the useful one unless
        # yt-dlp actually recognised the link.
        if errors and code == "unsupported":
            return emit("error", code=errors[0][0], message=errors[0][1])
        emit("error", code=code, message="yt-dlp: " + error)
    finally:
        if job.get("cookies"):
            try:
                os.remove(job["cookies"])
            except OSError:
                pass


def terminate(signum, frame):
    # Take ffmpeg and any other children down with us when the app cancels.
    try:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        os.killpg(0, signal.SIGTERM)
    finally:
        os._exit(143)


def main():
    sys.stdout = sys.stderr  # keep library chatter away from the event stream
    try:
        os.setpgrp()
    except OSError:
        pass
    signal.signal(signal.SIGTERM, terminate)
    with open(sys.argv[1], encoding="utf-8") as fp:
        job = json.load(fp)
    try:
        if job.get("mode") == "versions":
            versions()
        else:
            download(job)
    except BaseException as exc:
        if isinstance(exc, SystemExit):
            raise
        import traceback
        traceback.print_exc()
        emit("error", code="other", message="%s: %s" % (exc.__class__.__name__, exc))


if __name__ == "__main__":
    main()
