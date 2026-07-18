#!/usr/bin/env python3
import sys
import json

print('=== MESSAGE VALIDATION ===\n')
symbols_seen = set()
all_valid = True
message_count = 0

for line in sys.stdin:
    line = line.strip()
    if not line or 'Processed a total' in line:
        continue

    message_count += 1

    try:
        msg = json.loads(line)

        # Check required fields
        required_fields = ['symbol', 'price', 'volume', 'timestamp']
        missing = [f for f in required_fields if f not in msg]

        if missing:
            print(f'Message {message_count}: ❌ MISSING FIELDS: {missing}')
            all_valid = False
        else:
            symbols_seen.add(msg['symbol'])
            print(f'Message {message_count}: ✓ Valid')
            print(f'  Symbol: {msg["symbol"]}')
            print(f'  Price: ${msg["price"]:.2f}')
            print(f'  Volume: {msg["volume"]:.2e}')
            print(f'  Timestamp: {msg["timestamp"]}')
            print()

    except json.JSONDecodeError as e:
        print(f'Message {message_count}: ❌ INVALID JSON - {e}')
        print(f'  Raw: {line[:100]}...')
        all_valid = False

print(f'\n=== SUMMARY ===')
print(f'Messages processed: {message_count}')
print(f'Unique symbols: {sorted(symbols_seen)}')
print(f'Total unique symbols: {len(symbols_seen)}')
print(f'All messages valid: {"YES ✓" if all_valid else "NO ❌"}')
