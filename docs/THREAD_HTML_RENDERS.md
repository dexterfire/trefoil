# Thread HTML renders

Agent `html_render` pages (T3 Code's MCP tool) show inline in a thread as their own always-visible timeline row, placed at the tool call's ordinal position. They are never folded into a Work/Activities group and are not duplicated as an activity row. `html_preview` is agent-only (it returns a screenshot) and shows nothing in the thread.

## Data shape and detection

- `ToolActivity.htmlRender: HtmlRenderReference?` is the only detection signal. Parsing is in `core/model` `HtmlRenderParsing.kt`; projection sets it in `core/session` `PresentationProjection.kt`.
- A completed `dynamic_tool` item whose name is T3's `html_render` (any recognised MCP/namespace prefix, such as `mcp__t3-code__html_render`) or OpenCode code mode's `execute`. Errored output (`isError`) produces no render.
- The wire output is compacted to `{htmlRender: {attachmentId, title, height, heights?}}`; hydrated output is the MCP envelope (`structuredContent.htmlRender`). Envelope reads are bounded like T3's.
- Reference rules mirror T3: `attachmentId` 1..256 chars; `title` trimmed, at most 200 chars, "HTML" when blank; `height` clamped to 80..2000; `heights` at most 24 `[width, height]` pairs sorted by width. Unknown fields are tolerated.
- `toString()` on the reference and loader results is redacted. Never log HTML, titles, attachment ids or URLs.

## Fetch path

- The HTML is a `text/html` attachment. `HtmlRenderReference.file` feeds the existing `TrefoilClient.downloadFile`, which calls `assets.createUrl` (resource `_tag` `attachment`) and downloads the signed URL. No new RPC.
- `downloads/HtmlRenderLoader.kt` keeps pages in memory only: never disk or MediaStore. LRU of 8 MiB keyed by (environment, thread, file identity); 10 MiB source cap (over it is `TooLarge`); decoded as UTF-8.
- It fetches only while connected and still on the same environment and thread (the `InlineImageLoader` rule). Cached pages still render offline.
- The WebView receives the decoded document from memory, so it never sees the bearer token, the signed URL or the tunnel port.

## WebView security policy (`ui/htmlrender/HtmlRenderWebView.kt`)

- The main document is served by `shouldInterceptRequest` at the synthetic origin `https://html-render.trefoil.invalid/<sha256(attachmentId)[0..16]>/index.html`. Any other main-frame request, any redirect, and any non-GET request is answered with an empty 403.
- Settings: JavaScript on; DOM storage, database, file and content access, file-URL access and geolocation off; no multiple windows, no JS-opened windows; mixed content never; no cache; cookies never accepted (the jar is cleared once per process); algorithmic darkening off; no `addJavascriptInterface`.
- Permission requests are denied, file choosers return nothing, downloads are ignored, SSL errors, HTTP auth and client-cert requests are cancelled.
- The served document carries a CSP header (`connect-src https: data: blob:; form-action 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'`), so there are no WebSockets, frames, workers or form posts, `no-store`, `nosniff` and `no-referrer`.
- Subresources (`HtmlRenderPolicy`): GET only; `https:` with no userinfo, plus `data:` and `blob:`. Refused: loopback, `localhost`, `.local`/`.internal`/`.home.arpa`, single-label hosts, private/link-local/CGNAT/ULA/NAT64 literals, numeric hosts that are not plain dotted quads, and the environment's current hosts (read live from `HtmlRenderLoader.networkAccess`).
- Allowed https subresources are fetched by the app (`HtmlRenderSubresources.kt`), never by the WebView network stack: a dedicated OkHttp client with no auth, cookies, cache or proxy. Its `Dns` rejects a name if any answer is non-public, and a network interceptor re-checks the connected address. Redirects are followed manually (at most 5), re-checking scheme, host and address on every hop. Only Accept, Accept-Language and Origin are forwarded; Set-Cookie is dropped; responses are capped at 10 MiB.
- `Range` is never forwarded: the full resource is fetched (still capped) and returned as 200, and the WebView slices byte-range requests itself by seeking into that stream. An upstream 206 fails.
- Every remote hop requires a live connection to the same environment and thread, otherwise it gets an empty 403. The gate is checked before each hop, again after connect right before the request is written (a network interceptor reading the request's tagged gate; untagged requests are refused), before the body is read, and before the result reaches the WebView. Cached pages still render offline, without remote subresources.
- Each page (WebView) has its own `HtmlRenderFetchSession`: at most 6 concurrent fetches, every OkHttp call tracked. Releasing the WebView cancels them all and refuses later requests; losing the connection or thread (observed from client state) cancels running calls. Results of invalidated fetches are discarded as an empty 403.
- A redirected stylesheet (`text/css`) is served under its original URL, so its relative `url(...)` and `@import` targets are rewritten to absolute URLs resolved against the final URL (charset kept; `data:`, absolute, `#fragment` and escaped targets untouched). Other redirected types (such as JS modules with relative imports) still resolve against the original URL, because `WebResourceResponse` cannot carry a new base URL.
- Navigation: `shouldOverrideUrlLoading` always returns true. A gesture-initiated http(s) link opens in the user's browser; everything else is dropped.
- A renderer crash releases and destroys the WebView and shows the failed state. Each page uses one WebView that is not reused.

## Theming

`htmlRenderTheme` maps Trefoil colors onto the CSS variable names T3's bootstrap applies, with appearance following the app's light/dark. The initial theme travels in the URL fragment (`#t3-theme=…`). On a live theme change, `evaluateJavascript` posts T3's `ui/notifications/host-context-changed` message to `window`.

## Sizing and full screen

- Live content height comes from an appended script that reports `scrollHeight` through an origin-restricted `WebViewCompat.addWebMessageListener` (only when `WEB_MESSAGE_LISTENER` is supported). Without it the fallback is a port of T3's `htmlRenderFrameHeight` from the agent hint and measured `heights`.
- Inline height is the fit height capped at 2000 dp. A taller page shows its top with a fade and "Open full screen". The inline frame does not claim the conversation's vertical scroll.
- The header's full-screen button opens a dialog with a close bar. It scrolls and pinch-zooms using the in-memory bytes already held, with no refetch.

## States

Loading, Offline (not cached: "Reconnect to load “title”"), Failed ("Unable to load “title”", Retry while connected), TooLarge, Ready. `HtmlRenderCardContent` is stateless; in `LocalInspectionMode` Ready shows a placeholder tagged `html-render-placeholder`.

## Tests

`core/model` `HtmlRenderParsingTest`; `core/session` `HtmlRenderProjectionTest`; app `HtmlRenderLoaderTest`, `HtmlRenderPolicyTest`, `HtmlRenderSubresourcesTest`, `HtmlRenderThemeTest`, `HtmlRenderCardTest`; timeline coverage in `ConversationTimelineTest`. Screenshots are in `HtmlRenderScreenshots.kt` (see [Screenshot testing](SCREENSHOT_TESTING.md)); WebViews do not render there, so Ready is the placeholder and the conversation shot shows the row offline. Use fixtures only, never real user threads.
