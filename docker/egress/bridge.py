"""Internal streaming TLS transport. Never logs targets, headers, bodies or proxy credentials."""
import asyncio
import hmac
import logging
import os
from contextlib import asynccontextmanager
from urllib.parse import urlsplit

from aiohttp import web
from curl_cffi.requests import AsyncSession
from multidict import CIMultiDict

PROFILES = frozenset(("chrome136", "safari18_4"))
PROFILE_TARGETS = {"chrome136": "chrome136", "safari18_4": "safari184"}
HOP_HEADERS = frozenset(("host", "connection", "keep-alive", "proxy-authenticate",
    "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "content-length"))
SECRET = os.environ.get("STARRY_TLS_SECRET", "")
MAX_BODY = 16 * 1024 * 1024
SLOTS = asyncio.Semaphore(16)
BODY_BUDGET = 64 * 1024 * 1024
body_available = BODY_BUDGET
body_condition = asyncio.Condition()


@asynccontextmanager
async def body_capacity(length):
    global body_available
    reserved = min(length if length is not None else MAX_BODY, MAX_BODY)
    async with body_condition:
        await body_condition.wait_for(lambda: body_available >= reserved)
        body_available -= reserved
    try:
        yield
    finally:
        async with body_condition:
            body_available += reserved
            body_condition.notify_all()


async def forward(request):
    if not SECRET or not hmac.compare_digest(request.headers.get("X-Starry-Bridge-Key", ""), SECRET):
        return web.Response(status=401, headers={"X-Starry-Egress-Error": "authentication"})
    target = request.headers.get("X-Starry-Target", "")
    proxy = request.headers.get("X-Starry-Proxy", "")
    profile = request.headers.get("X-Starry-Tls-Profile", "")
    try:
        parsed = urlsplit(target)
        if parsed.scheme not in ("https", "http") or not parsed.hostname or parsed.username or parsed.fragment:
            raise ValueError()
        if proxy and (urlsplit(proxy).scheme not in ("http", "socks5", "socks5h") or not urlsplit(proxy).hostname):
            raise ValueError()
        if profile not in PROFILES:
            raise ValueError()
    except ValueError:
        return web.Response(status=400, headers={"X-Starry-Egress-Error": "invalid-route"})
    headers = [(key, value) for key, value in request.headers.items()
        if key.lower() not in HOP_HEADERS and not key.lower().startswith("x-starry-")]
    async with SLOTS, body_capacity(request.content_length):
        body = await request.read()
        async with AsyncSession(trust_env=False, max_clients=1) as session:
            upstream = None
            output = None
            try:
                upstream = await session.request(request.method, target, headers=headers, data=body,
                    proxy=proxy or None, impersonate=PROFILE_TARGETS[profile], allow_redirects=False, stream=True,
                    timeout=300, verify=True, default_headers=False)
                response_headers = CIMultiDict()
                # curl decompresses the stream, so encoded framing headers no longer describe it.
                for key, value in upstream.headers.multi_items():
                    if key.lower() not in HOP_HEADERS | {"content-encoding"} and not key.lower().startswith("x-starry-"):
                        response_headers.add(key, value)
                response_headers["X-Starry-Bridge-Response"] = "1"
                output = web.StreamResponse(status=upstream.status_code, headers=response_headers)
                await output.prepare(request)
                async for chunk in upstream.aiter_content():
                    await output.write(chunk)
                await output.write_eof()
                return output
            except (asyncio.CancelledError, ConnectionResetError):
                raise
            except Exception as error:
                logging.warning("TLS forwarding failed (%s)", type(error).__name__)
                if output is not None and output.prepared:
                    output.force_close()
                    if request.transport:
                        request.transport.close()
                    return output
                return web.Response(status=502, headers={"X-Starry-Egress-Error": "transport"})
            finally:
                if upstream is not None:
                    await upstream.aclose()


async def health(request):
    return web.json_response({"profiles": sorted(PROFILES)})


app = web.Application(client_max_size=MAX_BODY)
app.router.add_get("/health", health)
app.router.add_route("*", "/forward", forward)
if __name__ == "__main__":
    if not SECRET:
        raise SystemExit("STARRY_TLS_SECRET must be configured")
    web.run_app(app, host=os.environ.get("STARRY_TLS_BIND", "127.0.0.1"), port=9091, access_log=None, handler_cancellation=True)
