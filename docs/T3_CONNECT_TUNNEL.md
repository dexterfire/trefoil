# T3 Connect routes

A T3 machine can have several addresses. Trefoil tries them in order and uses the first one that answers as the same machine. You do not pick a route yourself.

This page describes the intended behaviour. It does not claim that every path is verified on every network.

## Routes

Trefoil tries these routes in this order:

1. **Local network (LAN):** a private address on your Wi-Fi, such as `192.168.1.20`.
2. **Tailscale:** the machine's tailnet address, such as a `.ts.net` name.
3. **HTTPS:** any other public HTTPS address you paired with.
4. **SSH:** the SSH login you saved for the machine.
5. **T3 Connect tunnel:** `https://prod-<id>.t3coderelay.com/`. It works away from home.

Before Trefoil sends your session to a route, it checks that the route belongs to the same T3 environment. A route that answers as a different machine is skipped. A route that rejects your session stops the attempt on every route, because the session is not valid for that machine.

The same session works on every route of the same machine. You pair once, and the other routes are learned from the machine.

## Turn on T3 Connect

On the machine running T3 Code:

1. Open **Settings → Sharing & access**.
2. Turn on **T3 Connect**.

The tunnel address appears once the tunnel is up.

## Pair once, then Trefoil learns the rest

Pair Trefoil once from any address. This works with a LAN, Tailscale, HTTPS or tunnel link. A pairing link from the tunnel looks like `https://prod-<id>.t3coderelay.com/pair#token=…`.

After the first connection, the machine tells Trefoil its other addresses. Trefoil stores them and uses them on later connections. You do not need to add them by hand.

If T3 Connect is off on the machine, no tunnel address is learned. The machine's settings in Trefoil then show a short hint. Turn on T3 Connect in T3 Code, then connect once more.

## Switching back to a faster route

When Trefoil is connected over a slower route, it checks for a faster one:

- every 60 seconds, and
- when your phone's network changes, such as joining Wi-Fi.

If a faster route answers, Trefoil reconnects through it. Trefoil waits until no message, action, download or screen is in progress before it switches, so a switch never replays or drops a send. If a route does not answer, Trefoil waits longer before checking it again. A route whose switch failed is not tried again for 5 minutes.

If an open connection drops unexpectedly while the app is in the foreground, Trefoil tries the whole route list again up to three times, and waits for a network first when the phone is offline. It never reconnects after you disconnect yourself.

## Local network HTTP needs consent

HTTPS is always preferred. A LAN route that uses plain HTTP is off until you allow it for that machine.

To allow it, open the machine's settings in Trefoil and turn on **Use local network (unencrypted HTTP)**. The choice is saved for that machine only.

- Only private IPv4 addresses such as `192.168.1.20` are allowed.
- Public HTTP is always blocked.
- A machine paired through a plain HTTP LAN address already has this consent, so the switch is not shown for it.
- Plain HTTP is not encrypted by TLS. Use it only on a network you trust.

## Not supported yet

- **Relay sign-in:** T3 Connect relay sign-in needs T3's account system. Trefoil cannot sign in to the relay yet. Tunnel routes learned after pairing still work.
- **Push notifications:** remote push is not built yet. While Trefoil is connected to the machine, it shows local notifications for agent activity.
- **QR scanning:** not built yet. Paste the pairing link instead.
