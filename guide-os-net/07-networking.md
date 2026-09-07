# Module 7 — Networking: TCP/IP, UDP, sockets, multicast, kernel bypass

The listed requirement: "basic networking like TCP/IP and UDP." This module gives you the model, the protocols, the socket API, and the HFT-specific reality (why HFT often abandons the kernel stack entirely).

## The layered model (TCP/IP stack)

Data is wrapped in headers as it goes down the stack, unwrapped going up:

| Layer | Unit | Examples | Concern |
|-------|------|----------|---------|
| Application | message | market data feed, FIX, HTTP | your protocol |
| Transport | segment/datagram | **TCP, UDP** | end-to-end delivery |
| Network | packet | **IP** | addressing, routing across networks |
| Link | frame | Ethernet | one physical hop, MAC addresses |

- **IP** (network layer): addresses (IPv4/IPv6) and routing. **Best-effort, unreliable** — packets may be lost, duplicated, reordered. No guarantees. TCP/UDP sit on top.
- Each layer adds its header; the NIC ultimately sends Ethernet frames on the wire.

## TCP vs UDP — the core distinction

| | **TCP** | **UDP** |
|-|---------|---------|
| Connection | connection-oriented (handshake) | connectionless (just send) |
| Reliability | guaranteed delivery, retransmits lost data | no guarantee — fire and forget |
| Ordering | in-order delivery | may arrive out of order |
| Flow/congestion control | yes (windowing, backoff) | none |
| Overhead | higher (ACKs, state, headers) | minimal |
| Latency | higher & more variable (retransmits, Nagle) | lower & more predictable |
| Use | reliability matters (orders, login, files) | speed matters, loss tolerable (market data) |

### TCP: reliability mechanics (know these)

- **3-way handshake** to connect: SYN → SYN-ACK → ACK. Costs a round trip before any data.
- **Sequence numbers + ACKs**: receiver acknowledges bytes; unacked data is retransmitted → reliable but adds latency on loss.
- **Sliding window / flow control**: receiver advertises how much it can accept; sender limits in-flight data.
- **Congestion control** (slow start, congestion avoidance): backs off on loss — great for the internet, bad for predictable latency.
- **Nagle's algorithm**: batches small sends to reduce tiny packets — *adds latency*. HFT **disables it** with `TCP_NODELAY`.

### UDP: why HFT loves it for market data

Market data is a firehose of price updates. If one update is lost, you often don't want a retransmit — by the time it arrives it's stale; you want the *next* fresh update. UDP's no-retransmit, no-ordering, minimal-overhead model fits: **lowest, most predictable latency**. Exchanges multicast market data over UDP.

The tradeoff: *your application* handles gaps (sequence numbers in the feed, request a snapshot/replay if you detect a gap). You trade kernel reliability for control and speed.

## The socket API (the syscall interface)

A **socket** is the OS abstraction for a network endpoint (a file descriptor — Module 1). The classic calls (each a syscall):

```cpp
// TCP server (sketch)
int fd = socket(AF_INET, SOCK_STREAM, 0);   // SOCK_STREAM = TCP; SOCK_DGRAM = UDP
bind(fd, ...);                               // attach to an address/port
listen(fd, backlog);
int conn = accept(fd, ...);                  // blocks until a client connects
recv(conn, buf, len, 0);                     // read (syscall — Module 1)
send(conn, buf, len, 0);                     // write

// UDP: socket(SOCK_DGRAM) then recvfrom / sendto (no connection, no accept)
```

Key options HFT sets:
- `TCP_NODELAY` — disable Nagle (send immediately).
- `SO_RCVBUF`/`SO_SNDBUF` — buffer sizes.
- `SO_REUSEADDR`, and for multicast: `IP_ADD_MEMBERSHIP` to join a group.

### Blocking vs non-blocking vs multiplexing

- **Blocking** `recv` sleeps the thread until data arrives (context switch — Module 1).
- **Non-blocking** returns immediately (`EWOULDBLOCK` if nothing) → enables **busy-polling**.
- **I/O multiplexing** — `epoll` (Linux), `kqueue` (BSD) — watch many sockets with one thread, get told which are ready. The scalable server pattern (handles 100k connections without 100k threads). `select`/`poll` are older, O(n) variants.

## Multicast — one sender, many receivers

Exchanges send each market-data update **once** to a multicast group; the network replicates it to all subscribers. Efficient (sender doesn't send N copies) and low-latency. Receivers join the group (`IP_ADD_MEMBERSHIP`) and `recvfrom`. Almost always UDP.

## Kernel bypass — the HFT endgame

Every `recv`/`send` is a syscall + data copy + the kernel's network stack processing + (normally) an interrupt (Module 1). That's microseconds of unpredictable latency. HFT removes the kernel from the path:

- **DPDK** (Data Plane Development Kit), **Solarflare/Onload**, **Mellanox VMA**: user-space networking. The application polls the NIC directly, packets DMA straight into user memory, no syscall, no kernel stack, no interrupt.
- The thread **busy-polls** the NIC (burns a core) for the lowest, flattest latency.
- Result: receive-to-decision latency drops from ~µs (kernel) to hundreds of ns or less.
- Extreme end: **FPGA** NICs parse the feed in hardware before the CPU even sees it (sub-microsecond).

This is why your research doc lists DPDK — it's the standard "ultra-low-latency" networking answer.

## Tradeoffs / interview "why"

- TCP (reliable, ordered, higher/variable latency) vs UDP (unreliable, minimal, predictable) — and *why market data is UDP, order entry is often TCP* (or a reliable protocol atop UDP).
- TCP mechanics: handshake, ACK/retransmit, sliding window, congestion control, Nagle + `TCP_NODELAY`.
- Sockets are file descriptors; `recv`/`send` are syscalls (the cost).
- `epoll` for scalable multiplexing; blocking vs non-blocking vs busy-poll.
- Multicast for one-to-many feeds.
- Kernel bypass (DPDK/Onload) removes syscall+stack+interrupt for the lowest latency — the marquee HFT networking topic.

## In the trading system

Market data arrives via **UDP multicast**, received through a **kernel-bypass** busy-polling I/O thread (no syscalls, interrupts routed off its core — Module 8). The thread parses packets from a DMA'd buffer (zero-copy, `std::span` views — `../guide/18`) and pushes normalized messages to the matching thread via a lock-free SPSC queue (`../guide/16`). Order entry to the exchange typically uses TCP (or a reliable session protocol) with `TCP_NODELAY`. The whole design is about getting a packet from the wire to a trading decision with the fewest, most predictable nanoseconds.
