import pyaudiowpatch as pyaudio
import socket
import threading
import json
import time
import opuslib

# ── Network config ───────────────────────────────────────
PHONE_IP       = "10.129.176.255"
PORT           = 59100
DISCOVERY_PORT = 5353

# ── Opus config ──────────────────────────────────────────
FRAME_SIZE  = 960   # 20ms at 48000Hz — best latency/quality tradeoff
CHANNELS    = 2
SAMPLE_RATE = 48000
BIT_DEPTH   = 16

# ── Auto-detect WASAPI loopback ──────────────────────────
p      = pyaudio.PyAudio()
wasapi = p.get_host_api_info_by_type(pyaudio.paWASAPI)

default_output  = p.get_device_info_by_index(wasapi['defaultOutputDevice'])
loopback_device = None

for i in range(p.get_device_count()):
    dev = p.get_device_info_by_index(i)
    if dev.get('isLoopbackDevice') and default_output['name'] in dev['name']:
        loopback_device = dev
        break

if loopback_device is None:
    print("ERROR: No loopback device found.")
    exit(1)

DEVICE_INDEX = int(loopback_device['index'])
SAMPLE_RATE  = int(loopback_device['defaultSampleRate'])

print(f"Device      : {loopback_device['name']}")
print(f"Sample Rate : {SAMPLE_RATE} Hz (auto-detected)")
print(f"Channels    : {CHANNELS}")
print(f"Frame Size  : {FRAME_SIZE} samples = 20ms")

# ── Opus encoder ─────────────────────────────────────────
encoder = opuslib.Encoder(SAMPLE_RATE, CHANNELS, opuslib.APPLICATION_AUDIO)
encoder.bitrate = 128000  # 128kbps — transparent quality

# ── Open loopback stream ─────────────────────────────────
stream = p.open(
    format=pyaudio.paInt16,
    channels=CHANNELS,
    rate=SAMPLE_RATE,
    input=True,
    input_device_index=DEVICE_INDEX,
    frames_per_buffer=FRAME_SIZE
)

# ── UDP socket ───────────────────────────────────────────
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

# ── Auto-discovery beacon ────────────────────────────────
def broadcast_beacon():
    beacon_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    beacon_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

    temp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    temp.connect(("8.8.8.8", 80))
    my_ip = temp.getsockname()[0]
    temp.close()

    payload = json.dumps({
        "service"   : "AudioShare",
        "version"   : "1.0",
        "port"      : PORT,
        "ip"        : my_ip,
        "sampleRate": SAMPLE_RATE,
        "bitDepth"  : BIT_DEPTH,
        "channels"  : CHANNELS,
        "codec"     : "opus"
    }).encode()

    while True:
        beacon_sock.sendto(payload, ('<broadcast>', DISCOVERY_PORT))
        time.sleep(1)

threading.Thread(target=broadcast_beacon, daemon=True).start()

# ── Main stream loop ─────────────────────────────────────
seq = 0
print(f"\nStreaming to {PHONE_IP}:{PORT} with Opus @ 128kbps...")

try:
    while True:
        pcm_chunk = stream.read(FRAME_SIZE, exception_on_overflow=False)
        encoded   = encoder.encode(pcm_chunk, FRAME_SIZE)
        seq       = (seq + 1) % 65536
        packet    = seq.to_bytes(2, 'big') + encoded
        sock.sendto(packet, (PHONE_IP, PORT))

except KeyboardInterrupt:
    print("Stopped.")
finally:
    stream.stop_stream()
    stream.close()
    p.terminate()
    sock.close()