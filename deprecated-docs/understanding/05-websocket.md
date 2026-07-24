# Understanding WebSocket

## The Problem: Real-Time Updates to Browser

**Goal:** Show live price updates in the browser as they happen.

### Approach 1: HTTP Polling (Bad)

**How it works:**
```javascript
// Browser asks server every second
setInterval(() => {
    fetch('/api/tokens/BTC/price')
        .then(res => res.json())
        .then(data => updateChart(data));
}, 1000);
```

**Problems:**
1. **Wasteful** - 1,000 requests/second across all users
2. **Latency** - Updates delayed by polling interval (1 second)
3. **Server load** - Must handle constant requests even when nothing changed
4. **Not scalable** - 10,000 users = 10 million requests/second

### Approach 2: Long Polling (Better, Still Bad)

**How it works:**
```javascript
function poll() {
    fetch('/api/tokens/BTC/price')
        .then(res => res.json())
        .then(data => {
            updateChart(data);
            poll(); // Immediately poll again
        });
}
```

Server holds connection open until data is available.

**Problems:**
1. **Still uses HTTP** - overhead of HTTP headers
2. **Reconnection overhead** - new connection after each message
3. **Complex server logic** - must manage hanging connections

### Approach 3: WebSocket (Best)

**How it works:**
```javascript
// Open persistent connection (once)
const ws = new WebSocket('ws://localhost:8080/ws');

// Receive messages as they arrive
ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    updateChart(data);
};
```

**Benefits:**
1. **Persistent connection** - opened once, stays open
2. **Bi-directional** - server can push, client can send
3. **Low overhead** - no HTTP headers on each message
4. **Real-time** - updates as soon as available (no polling delay)

---

## What is WebSocket?

A **communication protocol** that provides **full-duplex** communication over a single TCP connection.

### HTTP vs WebSocket

**HTTP (Request-Response):**
```
Client → [Request]  → Server
Client ← [Response] ← Server
(Connection closes)

Client → [Request]  → Server
Client ← [Response] ← Server
(Connection closes)
```

Each request requires:
- TCP handshake
- HTTP headers (~500 bytes)
- TLS handshake (if HTTPS)

**WebSocket (Persistent):**
```
Client → [Handshake]  → Server
Client ← [Handshake]  ← Server
(Connection stays open)

Client ← [Message 1] ← Server
Client ← [Message 2] ← Server
Client → [Message 3] → Server
Client ← [Message 4] ← Server
...
(Connection stays open until explicitly closed)
```

After initial handshake:
- No overhead
- Instant delivery
- Both sides can send anytime

---

## WebSocket Lifecycle

### 1. Handshake (HTTP Upgrade)

Client sends HTTP request with special headers:
```http
GET /ws HTTP/1.1
Host: localhost:8080
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Sec-WebSocket-Version: 13
```

Server responds:
```http
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```

**101 Switching Protocols** = connection upgraded from HTTP to WebSocket

### 2. Data Transfer

Client and server exchange **frames** (small packets):

```
Frame format:
[Header (2-14 bytes)][Payload]

Types:
- Text frame (UTF-8 text)
- Binary frame (arbitrary binary data)
- Ping frame (heartbeat)
- Pong frame (heartbeat response)
- Close frame (graceful shutdown)
```

### 3. Connection Close

Either side can send a **close frame**:
```
Client → [Close frame (reason code)] → Server
Client ← [Close frame (confirmation)] ← Server
```

Common close codes:
- `1000` - Normal closure
- `1001` - Going away (page refresh)
- `1006` - Abnormal closure (crash, network error)

---

## STOMP Protocol

**STOMP = Simple Text-Oriented Messaging Protocol**

A **higher-level protocol** on top of WebSocket that adds:
- **Topics** (like Kafka topics)
- **Subscriptions** (clients subscribe to topics)
- **Message routing** (server routes messages to subscribers)

### Why STOMP?

**Raw WebSocket:**
```javascript
// Client must parse every message
ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    
    if (msg.type === 'BTC_UPDATE') {
        // Handle BTC update
    } else if (msg.type === 'ETH_UPDATE') {
        // Handle ETH update
    }
    // Manual routing
};
```

**With STOMP:**
```javascript
// Subscribe to specific topics
stompClient.subscribe('/topic/BTC', (message) => {
    // Only BTC updates come here
    updateBTCChart(JSON.parse(message.body));
});

stompClient.subscribe('/topic/ETH', (message) => {
    // Only ETH updates come here
    updateETHChart(JSON.parse(message.body));
});
```

**Benefits:**
- **Topic-based routing** - like publish-subscribe pattern
- **Multiple subscriptions** - one connection, many topics
- **Message headers** - metadata separate from body
- **Acknowledgments** - confirm message receipt

---

## STOMP Message Format

### CONNECT (Client → Server)
```
CONNECT
accept-version:1.0,1.1,1.2
host:localhost

^@
```

### SUBSCRIBE (Client → Server)
```
SUBSCRIBE
id:sub-0
destination:/topic/BTC

^@
```

`destination` = topic to subscribe to

### MESSAGE (Server → Client)
```
MESSAGE
destination:/topic/BTC
content-type:application/json
subscription:sub-0

{"symbol":"BTC","price":50000.00,"timestamp":"2024-01-01T14:00:00Z"}
^@
```

### SEND (Client → Server)
```
SEND
destination:/app/trade
content-type:application/json

{"action":"BUY","symbol":"BTC","amount":1.0}
^@
```

---

## Our WebSocket Architecture

### Server Side (Spring Boot)

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")          // WebSocket endpoint
                .setAllowedOrigins("*")      // CORS
                .withSockJS();               // Fallback for old browsers
    }
    
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic"); // Topics prefix
        config.setApplicationDestinationPrefixes("/app"); // App endpoints
    }
}
```

### Message Publisher (Kafka → WebSocket)

```java
@Service
public class PriceUpdateService {
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @KafkaListener(topics = "candles-1m")
    public void onCandleUpdate(OHLCCandle candle) {
        // Send to all clients subscribed to /topic/{symbol}
        messagingTemplate.convertAndSend(
            "/topic/" + candle.getSymbol(),
            candle
        );
    }
}
```

### Client Side (React)

```typescript
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

// Create client
const stompClient = new Client({
    webSocketFactory: () => new SockJS('http://localhost:8080/ws'),
    
    onConnect: () => {
        // Subscribe to BTC updates
        stompClient.subscribe('/topic/BTC', (message) => {
            const candle = JSON.parse(message.body);
            updateChart(candle);
        });
    },
    
    onDisconnect: () => {
        console.log('Disconnected');
    }
});

// Connect
stompClient.activate();
```

---

## Subscription Management

### Problem: Too Many Messages

If client subscribes to all 1,000 tokens:
- 1,000 tokens × 1 update/second = **1,000 messages/second to browser**
- Browser can't render this fast
- Unnecessary network traffic

### Solution: Per-Client Subscriptions

```javascript
// Client subscribes only to tokens they're viewing
const watchlist = ['BTC', 'ETH', 'SOL'];

watchlist.forEach(symbol => {
    stompClient.subscribe(`/topic/${symbol}`, (message) => {
        updatePrice(symbol, JSON.parse(message.body));
    });
});
```

Server only sends to subscribed clients.

### Server-Side Throttling

For high-frequency updates, throttle sending:

```java
@Service
public class ThrottledPublisher {
    
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();
    private final Duration throttleInterval = Duration.ofMillis(100);
    
    @KafkaListener(topics = "candles-1m")
    public void onUpdate(OHLCCandle candle) {
        String key = candle.getSymbol();
        Instant now = Instant.now();
        
        // Check if enough time passed since last send
        Instant last = lastSent.get(key);
        if (last == null || Duration.between(last, now).compareTo(throttleInterval) > 0) {
            messagingTemplate.convertAndSend("/topic/" + key, candle);
            lastSent.put(key, now);
        }
    }
}
```

**Result:** Max 10 messages/second per token (instead of 60+)

---

## Connection Management

### Heartbeat

Keep connection alive and detect failures:

```java
@Override
public void configureMessageBroker(MessageBrokerRegistry config) {
    config.enableSimpleBroker("/topic")
          .setHeartbeatValue(new long[]{10000, 10000}); // 10 seconds
}
```

Server sends PING every 10 seconds. If no PONG received, closes connection.

### Reconnection

Client should automatically reconnect on failure:

```typescript
const stompClient = new Client({
    reconnectDelay: 5000,  // Retry after 5 seconds
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    
    onWebSocketClose: () => {
        console.log('Connection lost, reconnecting...');
    }
});
```

### Session Tracking

Track connected users:

```java
@Component
public class WebSocketEventListener {
    
    @EventListener
    public void handleConnect(SessionConnectedEvent event) {
        String sessionId = event.getMessage().getHeaders().get("simpSessionId");
        log.info("New WebSocket connection: {}", sessionId);
    }
    
    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        log.info("WebSocket disconnected: {}", sessionId);
    }
}
```

---

## Performance Considerations

### Memory Per Connection

Each WebSocket connection consumes:
- **TCP connection:** ~10 KB kernel buffer
- **Spring session:** ~5-10 KB heap memory
- **STOMP subscriptions:** ~1 KB per subscription

**1,000 concurrent users:**
- ~15 MB memory
- ~1,000 file descriptors

### Message Throughput

**Per connection:**
- **Receive:** 100-1,000 messages/sec
- **Send:** 100-1,000 messages/sec

**Total server:**
- 1,000 connections × 10 msg/sec = 10,000 msg/sec

### Scaling

For >10,000 connections, use:
1. **Load balancer** with sticky sessions
2. **Message broker** (Redis, RabbitMQ) for pub/sub across instances
3. **WebSocket clusters** (multiple servers)

---

## Key Takeaways

1. **WebSocket = persistent bi-directional connection**
2. **STOMP = messaging protocol** on top of WebSocket
3. **Topic-based subscriptions** - clients subscribe to specific topics
4. **Throttling** prevents overwhelming clients
5. **Heartbeat** keeps connection alive and detects failures
6. **Auto-reconnect** handles network issues
7. **Scales to ~10,000 connections** per server

---

## Next: Understanding Technical Indicators

See: `06-technical-indicators.md`
